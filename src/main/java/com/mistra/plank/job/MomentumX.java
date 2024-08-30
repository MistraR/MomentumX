package com.mistra.plank.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mistra.plank.common.config.PlankConfig;
import com.mistra.plank.common.util.StockUtil;
import com.mistra.plank.dao.MomentumDao;
import com.mistra.plank.dao.StockMapper;
import com.mistra.plank.dao.TradingRecordDao;
import com.mistra.plank.model.dto.StockRealTimePrice;
import com.mistra.plank.model.entity.Momentum;
import com.mistra.plank.model.entity.Stock;
import com.mistra.plank.model.entity.TradingRecord;
import com.mistra.plank.model.enums.AutomaticTradingEnum;
import com.mistra.plank.model.enums.ClearanceReasonEnum;
import com.mistra.plank.model.vo.trade.StockVo;
import com.mistra.plank.service.TradeApiService;
import com.mistra.plank.service.TradeService;
import com.mistra.plank.tradeapi.TradeResultVo;
import com.mistra.plank.tradeapi.request.GetStockListRequest;
import com.mistra.plank.tradeapi.request.SubmitRequest;
import com.mistra.plank.tradeapi.response.GetStockListResponse;
import com.mistra.plank.tradeapi.response.SubmitResponse;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.NamedThreadFactory;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @ author: rui.wang@mistra.com
 * @ description: 波动王子
 * @ date: 2024/8/29
 */
@Slf4j
@Component
public class MomentumX {

    private static final int availableProcessors = Runtime.getRuntime().availableProcessors();
    private final PlankConfig plankConfig;
    private final StockProcessor stockProcessor;
    private final MomentumDao momentumDao;
    private final TradingRecordDao tradingRecordDao;
    private final StockMapper stockMapper;
    private final TradeApiService tradeApiService;
    private final TradeService tradeService;

    public static final ThreadPoolExecutor MOMENTUM_POOL = new ThreadPoolExecutor(availableProcessors, availableProcessors, 100L,
            TimeUnit.SECONDS, new SynchronousQueue<>(), new NamedThreadFactory("MX-", false));

    public static final ConcurrentHashMap<String, Momentum> MOMENTUM_CACHE = new ConcurrentHashMap<>();

    public MomentumX(PlankConfig plankConfig, StockProcessor stockProcessor, MomentumDao momentumDao, TradingRecordDao tradingRecordDao,
                     StockMapper stockMapper, TradeApiService tradeApiService, TradeService tradeService) {
        this.plankConfig = plankConfig;
        this.stockProcessor = stockProcessor;
        this.momentumDao = momentumDao;
        this.tradingRecordDao = tradingRecordDao;
        this.stockMapper = stockMapper;
        this.tradeApiService = tradeApiService;
        this.tradeService = tradeService;
    }

    @Scheduled(cron = "*/30 * * * * ?")
    public void begin() {
        List<Momentum> momentumList = momentumDao.selectList(new LambdaQueryWrapper<Momentum>().eq(Momentum::getAuto, true));
        List<StockVo> stockHoldings = getStockHoldings();
        if (CollectionUtils.isNotEmpty(stockHoldings)) {
            Map<String, Momentum> map = momentumList.stream().collect(Collectors.toMap(Momentum::getName, e -> e));
            stockHoldings.forEach(e -> {
                if (map.containsKey(e.getName())) {
                    Momentum momentum = map.get(e.getName());
                    momentum.setHoldNumber(e.getTotalVolume());
                    momentum.setAvailableNumber(e.getAvailableVolume());
                    momentum.setCostPrice(e.getCostPrice());
                    momentum.setProfitRate((e.getPrice().subtract(e.getCostPrice())).divide(e.getCostPrice(), 3, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
                    momentum.setProfit(e.getProfit());
                    momentumDao.updateById(momentum);
                } else {
                    log.info("新导入持仓数据:{}", JSONUtil.toJsonStr(e));
                    Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getName, e.getName()));
                    momentumDao.insert(Momentum.builder().code(stock.getCode()).name(e.getName()).auto(true)
                            .holdNumber(e.getTotalVolume()).availableNumber(e.getAvailableVolume())
                            .costPrice(e.getCostPrice()).profit(e.getProfit()).profitRate(e.getRate()).build());
                }
            });
        }
        momentumList = momentumDao.selectList(new LambdaQueryWrapper<Momentum>().eq(Momentum::getAuto, true));
        for (Momentum momentum : momentumList) {
            if (!MOMENTUM_CACHE.containsKey(momentum.getCode()) && momentum.getAuto()) {
                MOMENTUM_POOL.submit(new AutoMomentumXTask(momentum));
            }
        }
    }

    class AutoMomentumXTask implements Runnable {

        private Momentum momentum;

        AutoMomentumXTask(Momentum momentum) {
            this.momentum = momentum;
        }

        /**
         * 做T：今日买入的达到盈利目标，并且有可卖出持仓即可做T
         */
        @Override
        public void run() {
            try {
                Date today = new Date();
                while (isTradeTime()) {
                    momentum = momentumDao.selectById(momentum.getCode());
                    MOMENTUM_CACHE.put(momentum.getCode(), momentum);
                    //获取该股票所有未清仓持仓明细
                    List<TradingRecord> tradingRecords =
                            tradingRecordDao.selectList(new LambdaQueryWrapper<TradingRecord>().eq(TradingRecord::getClearance, false)
                                    .eq(TradingRecord::getCode, momentum.getCode()));
                    //今日可卖
                    List<TradingRecord> todaySaleList =
                            tradingRecords.stream().filter(tradingRecord -> !DateUtils.isSameDay(tradingRecord.getBuyTime(), today
                            )).collect(Collectors.toList());
                    //今日已买入
                    List<TradingRecord> todayBuyList = tradingRecords.stream().filter(tradingRecord -> DateUtils.isSameDay(tradingRecord.getBuyTime(),
                            today)).collect(Collectors.toList());

                    StockRealTimePrice stockRealTimePrice = stockProcessor.getStockRealTimePriceByCode(momentum.getCode());

                    if (momentum.getAvailableNumber() > 0 && CollectionUtils.isNotEmpty(todaySaleList)) {
                        //有可卖出持仓
                        if (momentum.getWaveTrading()) {
                            //网格波动做T
                            waveTradingSale(todaySaleList, todayBuyList, momentum, stockRealTimePrice);
                        }
                        //执行正常止盈止损
                        sale(momentum, stockRealTimePrice, todaySaleList, todayBuyList);
                    }
                    if (momentum.getBuy()) {
                        //检查买入
                        if (momentum.getWaveTrading()) {
                            waveTradingBuy(momentum, stockRealTimePrice);
                        } else {
                            buy(momentum, stockRealTimePrice);
                        }
                    }
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                MOMENTUM_CACHE.remove(momentum.getCode());
                momentumDao.updateById(momentum);
            }
        }
    }

    /**
     * 网格波动做T
     */
    private void waveTradingSale(List<TradingRecord> todaySaleList, List<TradingRecord> todayBuyList, Momentum momentum,
                                 StockRealTimePrice stockRealTimePrice) {
        if (todaySaleList.size() + todayBuyList.size() <= 1) {
            //如果只剩1份底仓,不卖出
            return;
        }
        if (momentum.getNextSalePrice().doubleValue() <= stockRealTimePrice.getCurrentPrice()) {
            TradingRecord tradingRecord = todaySaleList.stream().min(Comparator.comparing(TradingRecord::getBuyPrice)).get();
            boolean saled = sale(momentum, stockRealTimePrice.getCurrentPrice(), Math.min(tradingRecord.getNumber(), momentum.getAvailableNumber()));
            if (!saled) {
                return;
            }
            tradingRecord.setProfit(BigDecimal.valueOf((stockRealTimePrice.getCurrentPrice() - tradingRecord.getBuyPrice().doubleValue()) * tradingRecord.getNumber().doubleValue()));
            tradingRecord.setClearance(true);
            tradingRecord.setSaleTime(new Date());
            tradingRecord.setSalePrice(BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()));
            tradingRecord.setClearanceReason(ClearanceReasonEnum.WAVE_TRADING_SALE_T.getDesc());
            tradingRecordDao.updateById(tradingRecord);
            todaySaleList.remove(tradingRecord);
            log.info("网格波动做T {} {}股 盈利{}", momentum.getName(), tradingRecord.getNumber(), tradingRecord.getProfit());

            List<TradingRecord> list = new ArrayList<>();
            list.addAll(todayBuyList);
            list.addAll(todaySaleList);
            //重新计算下一次卖出价格
            BigDecimal price = list.stream().min(Comparator.comparing(TradingRecord::getBuyPrice)).get().getBuyPrice();
            price = price.multiply(momentum.getTRate());
            if (price.doubleValue() < momentum.getNextSalePrice().doubleValue()) {
                momentum.setNextSalePrice(price);
            }
            //重新计算下一次买入价格
            BigDecimal suckRate = BigDecimal.valueOf(2).subtract(momentum.getTRate()).subtract(new BigDecimal("0.01"));
            price = BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()).multiply(suckRate);
            if (price.doubleValue() < momentum.getNextSuckPrice().doubleValue()) {
                momentum.setNextSuckPrice(price);
            }
        }
    }

    /**
     * 止盈止损
     */
    private void sale(Momentum momentum, StockRealTimePrice stockRealTimePrice, List<TradingRecord> todaySaleList, List<TradingRecord> todayBuyList) {
        if (CollectionUtils.isEmpty(todaySaleList)) {
            return;
        }
        if (stockRealTimePrice.isPlank()) {
            //封板,暂时不卖出
            return;
        }
        List<TradingRecord> saleList = new ArrayList<>();
        TradingRecord tradingRecord = todaySaleList.stream().min(Comparator.comparing(TradingRecord::getBuyPrice)).get();
        int saleNumber = 0;
        ClearanceReasonEnum clearanceReasonEnum = ClearanceReasonEnum.WAVE_TRADING_SALE_T;

        if (momentum.getProfitRate().doubleValue() <= plankConfig.getStopLossRate()) {
            log.error("{} 触发止损,自动卖出", momentum.getName());
            saleNumber = momentum.getAvailableNumber();
            clearanceReasonEnum = ClearanceReasonEnum.STOP_LOSE;
            saleList.addAll(todaySaleList);
        }

        if (Objects.nonNull(momentum.getFirstProfitSale()) && momentum.getFirstProfitSale().doubleValue() <= momentum.getProfitRate().doubleValue()) {
            log.error("{} 触发第一个止盈点,自动卖出一份持仓", momentum.getName());
            saleNumber = Math.min(momentum.getAvailableNumber(), tradingRecord.getNumber());
            clearanceReasonEnum = ClearanceReasonEnum.FIRST_PROFIT_SALE;
            saleList.add(tradingRecord);
            momentum.setFirstProfitSale(null);
        }

        if (Objects.nonNull(momentum.getSecondProfitSale()) && momentum.getSecondProfitSale().doubleValue() <= momentum.getProfitRate().doubleValue()) {
            log.error("{} 触发第二个止盈点,自动卖出一半可用持仓", momentum.getName());
            saleNumber = Math.min(momentum.getAvailableNumber(), tradingRecord.getNumber());
            clearanceReasonEnum = ClearanceReasonEnum.SECOND_PROFIT_SALE;
            saleList.add(tradingRecord);
            momentum.setSecondProfitSale(null);
        }

        if (momentum.getTodayPlank() && !stockRealTimePrice.isPlank()) {
            if (todaySaleList.size() + todayBuyList.size() <= 1) {
                //如果只剩1份底仓,不卖出
                return;
            } else {
                log.error("{} 触发炸板止盈点,自动卖出持仓", momentum.getName());
                saleNumber = momentum.getAvailableNumber();
                clearanceReasonEnum = ClearanceReasonEnum.RATTY_PLANK;
                saleList.addAll(todaySaleList);
            }
        }

        if (momentum.getProfit().doubleValue() > momentum.getProfitLimit().doubleValue() && !stockRealTimePrice.isPlank()) {
            log.error("{} 触发清仓止盈点,自动卖出全部可用持仓", momentum.getName());
            saleNumber = momentum.getAvailableNumber();
            clearanceReasonEnum = ClearanceReasonEnum.TAKE_PROFIT;
            saleList.addAll(todaySaleList);
        }

        if (saleNumber > 0) {
            if (sale(momentum, stockRealTimePrice.getCurrentPrice(), saleNumber)) {
                for (TradingRecord record : saleList) {
                    record.setClearance(true);
                    record.setSaleTime(new Date());
                    record.setClearanceReason(clearanceReasonEnum.getDesc());
                    record.setSalePrice(BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()));
                    record.setProfit(BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()).subtract(record.getBuyPrice()).multiply(BigDecimal.valueOf(record.getNumber())));
                    tradingRecordDao.updateById(record);
                }
            }
        }
    }

    private boolean sale(Momentum momentum, Double currentPrice, int amount) {
        SubmitRequest request = new SubmitRequest(1);
        request.setAmount(amount);
        //request.setPrice(stockRealTimePrice.getLimitDown());
        // 全面注册制后只能最多挂-2%价格卖单
        request.setPrice(BigDecimal.valueOf(currentPrice * 0.985).setScale(2, RoundingMode.HALF_UP).doubleValue());
        request.setStockCode(momentum.getCode().substring(2, 8));
        request.setZqmc(momentum.getName());
        request.setTradeType(SubmitRequest.S);
        request.setMarket(StockUtil.getStockMarket(request.getStockCode()));
        TradeResultVo<SubmitResponse> response = tradeApiService.submit(request);
        if (response.success()) {
            log.error("触发{}止盈、止损，交易成功!", momentum.getName());
            updateMomentum(momentum);
        } else {
            log.error("触发{}止盈、止损，交易失败!", momentum.getName());
        }
        return response.success();
    }

    /**
     * 滚动低吸买入策略
     */
    private void waveTradingBuy(Momentum momentum, StockRealTimePrice stockRealTimePrice) {
        if (momentum.getNextSuckPrice().doubleValue() >= stockRealTimePrice.getCurrentPrice()) {
            if (buyOrder(momentum, stockRealTimePrice)) {
                // 更新滚动做T的最低卖出价格
                BigDecimal sale = BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()).multiply(momentum.getTRate());
                if (sale.doubleValue() < momentum.getNextSalePrice().doubleValue()) {
                    momentum.setNextSalePrice(sale);
                }
                // 更新滚动做T的最高买入价格
                BigDecimal buy =
                        BigDecimal.valueOf(stockRealTimePrice.getCurrentPrice()).multiply(BigDecimal.valueOf(2).subtract(momentum.getTRate()));
                if (buy.doubleValue() > momentum.getNextSuckPrice().doubleValue()) {
                    momentum.setNextSuckPrice(buy);
                }
            }
        }
    }

    /**
     * 常规低吸买入策略
     */
    private void buy(Momentum momentum, StockRealTimePrice stockRealTimePrice) {
        boolean suck = false;
        if (Objects.nonNull(momentum.getFirstSuckRate()) &&
                momentum.getFirstSuckRate().doubleValue() < stockRealTimePrice.getIncreaseRate() &&
                DateUtils.getFragmentInHours(new Date(), Calendar.DAY_OF_YEAR) < 10) {
            suck = true;
            momentum.setFirstSuckRate(null);
        } else if (Objects.nonNull(momentum.getSecondSuckRate()) &&
                momentum.getSecondSuckRate().doubleValue() < stockRealTimePrice.getIncreaseRate() &&
                DateUtils.getFragmentInHours(new Date(), Calendar.DAY_OF_YEAR) < 11) {
            suck = true;
            momentum.setSecondSuckRate(null);
        } else if (Objects.nonNull(momentum.getThirdSuckRate()) &&
                momentum.getThirdSuckRate().doubleValue() < stockRealTimePrice.getIncreaseRate() &&
                DateUtils.getFragmentInHours(new Date(), Calendar.DAY_OF_YEAR) < 15) {
            suck = true;
            momentum.setThirdSuckRate(null);
        } else if (Objects.nonNull(momentum.getSpecialSuckPrice()) && momentum.getSpecialSuckPrice().doubleValue() >= stockRealTimePrice.getCurrentPrice()) {
            suck = true;
            momentum.setSpecialSuckPrice(null);
        }

        if (suck) {
            buyOrder(momentum, stockRealTimePrice);
        }
    }

    private boolean buyOrder(Momentum momentum, StockRealTimePrice stockRealTimePrice) {
        int sum = 0, amount = 1;
        while (sum <= plankConfig.getSingleTransactionLimitAmount()) {
            sum = (int) (amount++ * 100 * stockRealTimePrice.getCurrentPrice());
        }
        amount -= 2;
        if (amount >= 1) {
            double cost = amount * 100 * stockRealTimePrice.getCurrentPrice();
            if (momentum.getTotalMoney().intValue() + cost <= momentum.getCostLimit().doubleValue()) {
                return buy(momentum, amount * 100, stockRealTimePrice.getCurrentPrice());
            }
        }
        return false;
    }

    /**
     * 买入接口
     *
     * @param momentum Momentum
     * @param amount   amount
     * @param price    price
     * @return 挂单是否成功
     */
    private boolean buy(Momentum momentum, int amount, double price) {
        SubmitRequest request = new SubmitRequest(1);
        request.setAmount(amount);
        request.setPrice(BigDecimal.valueOf(price * 1.015).setScale(2, RoundingMode.HALF_UP).doubleValue());
        request.setStockCode(momentum.getCode().substring(2, 8));
        request.setZqmc(momentum.getName());
        request.setTradeType(SubmitRequest.B);
        request.setMarket(StockUtil.getStockMarket(request.getStockCode()));
        TradeResultVo<SubmitResponse> response = tradeApiService.submit(request);
        if (response.success()) {
            log.error("成功下单[{}],数量:{},价格:{}", momentum.getName(), amount, price);
            updateMomentum(momentum);
            tradingRecordDao.insert(TradingRecord.builder()
                    .code(momentum.getCode())
                    .name(momentum.getName())
                    .number(amount)
                    .buyPrice(new BigDecimal(price))
                    .profit(new BigDecimal(0))
                    .buyTime(new Date())
                    .clearance(false)
                    .automaticTradingType(AutomaticTradingEnum.SUCK.name())
                    .build());
        } else {
            log.error("下单[{}]失败,message:{}", momentum.getName(), response.getMessage());
        }
        return response.success();
    }

    /**
     * 更新某个票的持仓明细
     */
    private void updateMomentum(Momentum momentum) {
        List<StockVo> stockVos = getStockHoldings();
        Optional<StockVo> first = stockVos.stream().filter(e -> e.getName().equals(momentum.getName())).findFirst();
        if (first.isPresent()) {
            StockVo stockVo = first.get();
            momentum.setHoldNumber(stockVo.getTotalVolume());
            momentum.setAvailableNumber(stockVo.getAvailableVolume());
            momentum.setCostPrice(stockVo.getCostPrice());
            momentum.setProfit(stockVo.getProfit());
            momentum.setProfitRate(((stockVo.getCostPrice().subtract(stockVo.getPrice())).divide(stockVo.getCostPrice(), 2, RoundingMode.HALF_UP)));
            momentumDao.updateById(momentum);
        }
    }

    /**
     * 查询我的持仓
     */
    private List<StockVo> getStockHoldings() {
        GetStockListRequest request = new GetStockListRequest(1);
        TradeResultVo<GetStockListResponse> response = tradeApiService.getStockList(request);
        List<StockVo> list = new ArrayList<>();
        if (response.success()) {
            list = tradeService.getTradeStockList(response.getData());
        } else {
            log.error("查询我的持仓失败:{}", response.getMessage());
        }
        return list.stream().filter(e -> e.getTotalVolume() > 0).collect(Collectors.toList());
    }

    /**
     * 当前时间是否是交易时间 只判定了时分秒，没有判定非交易日（周末及法定节假日），因为我一般只交易日才会启动项目
     *
     * @return 是否是交易时间
     */
    public static boolean isTradeTime() {
        int week = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
        if (week == 6 || week == 0) {
            //0-周日,6-周六
            return false;
        }
        int hour = DateUtil.hour(new Date(), true);
        return (hour == 9 && DateUtil.minute(new Date()) >= 30) || (hour == 11 && DateUtil.minute(new Date()) < 30)
                || hour == 10 || hour == 13 || hour == 14;
    }
}