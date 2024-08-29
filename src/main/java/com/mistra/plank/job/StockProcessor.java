package com.mistra.plank.job;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mistra.plank.common.config.PlankConfig;
import com.mistra.plank.common.util.HttpUtil;
import com.mistra.plank.dao.DailyRecordMapper;
import com.mistra.plank.dao.StockMapper;
import com.mistra.plank.model.dto.StockRealTimePrice;
import com.mistra.plank.model.entity.DailyRecord;
import com.mistra.plank.model.entity.Stock;

import lombok.extern.slf4j.Slf4j;

/**
 * @author Mistra @ Version: 1.0
 * @ Time: 2021/11/18 22:09
 * @ Description: 更新股票每日成交量
 * @ Copyright (c) Mistra,All Rights Reserved
 * @ Github: https://github.com/MistraR
 * @ CSDN: https://blog.csdn.net/axela30w
 */
@Slf4j
@Component
public class StockProcessor {

    private final StockMapper stockMapper;
    private final DailyRecordMapper dailyRecordMapper;
    private final PlankConfig plankConfig;

    public StockProcessor(StockMapper stockMapper, DailyRecordMapper dailyRecordMapper, PlankConfig plankConfig) {
        this.stockMapper = stockMapper;
        this.dailyRecordMapper = dailyRecordMapper;
        this.plankConfig = plankConfig;
    }

    public void run(List<String> codes) {
        for (String code : codes) {
            try {
                StockRealTimePrice stockRealTimePrice = getStockRealTimePriceByCode(code);
                Stock exist = stockMapper.selectById(code);
                List<DailyRecord> dailyRecords = dailyRecordMapper.selectPage(new Page<>(1, 20),
                        new LambdaQueryWrapper<DailyRecord>().eq(DailyRecord::getCode, code)
                                .ge(DailyRecord::getDate, DateUtils.addDays(new Date(), -40))
                                .orderByDesc(DailyRecord::getDate)).getRecords();
                exist.setCurrentPrice(BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()));
                exist.setTransactionAmount(stockRealTimePrice.getTransactionAmount());
                exist.setMarketValue(stockRealTimePrice.getMarket().longValue());
                if (dailyRecords.size() >= 20) {
                    exist.setMa5(BigDecimal
                            .valueOf(dailyRecords.subList(0, 5).stream().map(DailyRecord::getClosePrice)
                                    .collect(Collectors.averagingDouble(BigDecimal::doubleValue))));
                    exist.setMa10(BigDecimal
                            .valueOf(dailyRecords.subList(0, 10).stream().map(DailyRecord::getClosePrice)
                                    .collect(Collectors.averagingDouble(BigDecimal::doubleValue))));
                    exist.setMa20(BigDecimal
                            .valueOf(dailyRecords.subList(0, 20).stream().map(DailyRecord::getClosePrice)
                                    .collect(Collectors.averagingDouble(BigDecimal::doubleValue))));
                }
                stockMapper.updateById(exist);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 获取某只股票的最新价格
     *
     * @param code code
     * @return StockRealTimePrice
     */
    public StockRealTimePrice getStockRealTimePriceByCode(String code) {
        String url = plankConfig.getXueQiuStockLimitUpPriceUrl().replace("{code}", code);
        String body = HttpUtil.getHttpGetResponseString(url, plankConfig.getXueQiuCookie());
        JSONObject data = JSON.parseObject(body).getJSONObject("data");
        if (Objects.nonNull(data) && Objects.nonNull(data.getJSONObject("quote"))) {
            JSONObject quote = data.getJSONObject("quote");
            return StockRealTimePrice.builder().currentPrice(quote.getDouble("current")).code(code)
                    .highestPrice(quote.getDouble("high")).lowestPrice(quote.getDouble("low"))
                    .isPlank(quote.getDouble("current").equals(quote.getDouble("limit_up")))
                    .increaseRate(quote.getDouble("percent")).limitDown(quote.getDouble("limit_down"))
                    .limitUp(quote.getDouble("limit_up")).transactionAmount(quote.getBigDecimal("amount"))
                    .volume(quote.getLong("volume")).market(quote.getBigDecimal("float_market_capital")).build();
        } else {
            return null;
        }
    }

}
