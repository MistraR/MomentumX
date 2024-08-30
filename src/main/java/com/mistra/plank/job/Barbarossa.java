package com.mistra.plank.job;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.collect.Lists;
import com.mistra.plank.common.config.PlankConfig;
import com.mistra.plank.common.util.HttpUtil;
import com.mistra.plank.dao.BkMapper;
import com.mistra.plank.dao.DailyIndexMapper;
import com.mistra.plank.dao.StockInfoDao;
import com.mistra.plank.dao.StockMapper;
import com.mistra.plank.model.entity.Bk;
import com.mistra.plank.model.entity.DailyIndex;
import com.mistra.plank.model.entity.Stock;
import com.mistra.plank.model.entity.StockInfo;
import com.mistra.plank.model.enums.AutomaticTradingEnum;

import cn.hutool.core.thread.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Mistra @ Version: 1.0
 * @ Time: 2021/11/18 22:09
 * @ Description: 涨停
 * @ Copyright (c) Mistra,All Rights Reserved
 */
@Slf4j
@Component
public class Barbarossa implements CommandLineRunner {

    private static final int availableProcessors = Runtime.getRuntime().availableProcessors();
    private final BkMapper bkMapper;
    private final StockMapper stockMapper;
    private final PlankConfig plankConfig;
    private final StockProcessor stockProcessor;
    private final DailyRecordProcessor dailyRecordProcessor;
    private final StockInfoDao stockInfoDao;
    private final DailyIndexMapper dailyIndexMapper;
    public static final ThreadPoolExecutor executorService = new ThreadPoolExecutor(availableProcessors * 2,
            availableProcessors * 2, 100L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5000), new NamedThreadFactory("WSA-", false));
    /**
     * 所有股票 key-code value-name
     */
    public static final HashMap<String, String> ALL_STOCK_MAP = new HashMap<>(4096);
    /**
     * 需要重点关注的股票 key-name value-Stock
     */
    public static final ConcurrentHashMap<String, Stock> TRACK_STOCK_MAP = new ConcurrentHashMap<>(32);
    /**
     * 是否开启监控中
     */
    private final AtomicBoolean monitoring = new AtomicBoolean(false);

    public Barbarossa(StockMapper stockMapper, BkMapper bkMapper, StockProcessor stockProcessor, PlankConfig plankConfig,
                      DailyRecordProcessor dailyRecordProcessor, StockInfoDao stockInfoDao, DailyIndexMapper dailyIndexMapper) {
        this.stockMapper = stockMapper;
        this.bkMapper = bkMapper;
        this.stockProcessor = stockProcessor;
        this.plankConfig = plankConfig;
        this.dailyRecordProcessor = dailyRecordProcessor;
        this.stockInfoDao = stockInfoDao;
        this.dailyIndexMapper = dailyIndexMapper;
    }

    /**
     * 启动前端服务 cd ./stock-web && npm start
     */
    @Override
    public void run(String... args) {
        List<Stock> stocks = stockMapper.selectList(new QueryWrapper<Stock>()
                // 默认过滤掉了北交所,科创板,ST
                .notLike("name", "%ST%").notLike("code", "%688%").notLike("name", "%退%")
                .notLike("name", "%st%").notLike("name", "%A%").notLike("name", "%N%")
                .notLike("name", "%U%").notLike("name", "%W%").notLike("code", "%BJ%"));
        stocks.forEach(e -> {
            if ((e.getShareholding() || e.getTrack())) {
                TRACK_STOCK_MAP.put(e.getName(), e);
            }
            ALL_STOCK_MAP.put(e.getCode(), e.getName());
        });
        bkMapper.update(Bk.builder().increaseRate(new BigDecimal(0)).build(), new LambdaUpdateWrapper<Bk>());
        monitor();
    }


    @Scheduled(cron = "0 */2 * * * ?")
    public void monitor() {
        if (plankConfig.getEnableMonitor() && MomentumX.isTradeTime() && !monitoring.get() && TRACK_STOCK_MAP.size() > 0) {
            monitoring.set(true);
            executorService.submit(this::monitorStock);
        }
    }

    private void monitorStock() {

    }

    /**
     * 15点后读取当日交易数据
     */
    @Scheduled(cron = "0 1 15 * * ?")
    private void analyzeData() {
        try {
            this.updateStockPool();
            CountDownLatch countDownLatch = new CountDownLatch(Barbarossa.ALL_STOCK_MAP.size());
            dailyRecordProcessor.run(Barbarossa.ALL_STOCK_MAP, countDownLatch);
            countDownLatch.await();
            List<List<String>> partition = Lists.partition(Lists.newArrayList(Barbarossa.ALL_STOCK_MAP.keySet()), 300);
            for (List<String> list : partition) {
                // 更新每支股票的成交额
                executorService.submit(() -> stockProcessor.run(list));
            }
            log.warn("每日涨跌明细、成交额、MA5、MA10、MA20更新完成");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 更新股票池
     */
    private void updateStockPool() {
        BigDecimal zero = new BigDecimal(0);
        Date now = new Date();
        boolean haveNext = true;
        int page = 1;
        while (haveNext) {
            String url = plankConfig.getUpdateAllStockUrl().replace("{page}", page + "");
            String body = HttpUtil.getHttpGetResponseString(url, plankConfig.getXueQiuCookie());
            JSONObject data = JSON.parseObject(body).getJSONObject("data");
            Integer total = data.getInteger("count");
            JSONArray list = data.getJSONArray("list");
            for (Object o : list) {
                try {
                    JSONObject jsonObject = (JSONObject) o;
                    Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getCode, jsonObject.getString("symbol")));
                    if (Objects.isNull(stock)) {
                        stock = Stock.builder().code(jsonObject.getString("symbol")).name(jsonObject.getString("name")).marketValue(jsonObject.getLongValue("mc")).currentPrice(jsonObject.getBigDecimal("current")).purchasePrice(zero).transactionAmount(zero).purchaseType(10).track(false).shareholding(false).abbreviation("").automaticTradingType(AutomaticTradingEnum.CANCEL.name()).buyAmount(0).suckTriggerPrice(zero).build();
                        stockMapper.insert(stock);
                        stockInfoDao.insert(StockInfo.builder().code(stock.getCode().substring(2, 8)).name(stock.getName()).exchange(stock.getCode().substring(0, 2).toLowerCase()).state(0).type(0).createTime(now).updateTime(now).abbreviation("").build());
                        dailyIndexMapper.insert(DailyIndex.builder().code(stock.getCode().toLowerCase()).date(now).preClosingPrice(zero).openingPrice(zero).highestPrice(zero).lowestPrice(zero).tradingValue(zero)
                                .closingPrice(zero).tradingVolume(jsonObject.getLongValue("volume")).rurnoverRate(zero).createTime(now).updateTime(now).build());
                        log.info("股票池新增股票 {}", stock.getName());
                    } else {
                        stock.setName(jsonObject.getString("name"));
                        stockMapper.updateById(stock);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (page * 30 < total) {
                page++;
            } else {
                haveNext = false;
            }
        }
    }

}
