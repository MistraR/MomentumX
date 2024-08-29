package com.mistra.plank.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
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
import com.mistra.plank.dao.TradingRecordDao;
import com.mistra.plank.model.dto.StockRealTimePrice;
import com.mistra.plank.model.entity.Momentum;
import com.mistra.plank.model.entity.TradingRecord;
import com.mistra.plank.model.enums.ClearanceReasonEnum;
import com.mistra.plank.service.TradeApiService;
import com.mistra.plank.tradeapi.TradeResultVo;
import com.mistra.plank.tradeapi.request.SubmitRequest;
import com.mistra.plank.tradeapi.response.SubmitResponse;

import cn.hutool.core.thread.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * @ author: rui.wang@yamu.com
 * @ description: 龙头波动套利
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
    private final TradeApiService tradeApiService;

    public static final ThreadPoolExecutor MOMENTUM_POOL = new ThreadPoolExecutor(availableProcessors, availableProcessors, 100L,
            TimeUnit.SECONDS, new SynchronousQueue<>(), new NamedThreadFactory("WSA-", false));

    public static final ConcurrentHashMap<String, Momentum> MOMENTUM_CACHE = new ConcurrentHashMap<>();

    public MomentumX(PlankConfig plankConfig, StockProcessor stockProcessor, MomentumDao momentumDao, TradingRecordDao tradingRecordDao,
                     TradeApiService tradeApiService) {
        this.plankConfig = plankConfig;
        this.stockProcessor = stockProcessor;
        this.momentumDao = momentumDao;
        this.tradingRecordDao = tradingRecordDao;
        this.tradeApiService = tradeApiService;
    }

    @Scheduled(cron = "*/30 * * * * ?")
    public void begin() {
        List<Momentum> momentumList = momentumDao.selectList(new LambdaQueryWrapper<Momentum>().eq(Momentum::getAuto, true));
        for (Momentum momentum : momentumList) {
            if (!MOMENTUM_CACHE.containsKey(momentum.getCode())) {
                MOMENTUM_POOL.submit(new AutoMomentumXTask(momentum));
            }
        }
    }

    class AutoMomentumXTask implements Runnable {

        private final Momentum momentum;

        AutoMomentumXTask(Momentum momentum) {
            this.momentum = momentum;
        }

        /**
         * 做T：今日买入的达到盈利目标即可做T
         */
        @Override
        public void run() {
            try {
                Date today = new Date();
                while (AutomaticTrading.isTradeTime()) {
                    MOMENTUM_CACHE.put(momentum.getCode(), momentum);
                    StockRealTimePrice stockRealTimePrice = stockProcessor.getStockRealTimePriceByCode(momentum.getCode());
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

                    if (momentum.getAvailableNumber() > 0) {
                        if (CollectionUtils.isNotEmpty(todayBuyList)) {
                            //做T
                            saleT(todaySaleList, todayBuyList, momentum, stockRealTimePrice);
                        } else {
                            //todayBuyList为空则今日没有买入，没有做T空间，执行正常止盈止损
                            sale(momentum, stockRealTimePrice);
                        }
                    }
                    //检查买入
                    buy(momentum, stockRealTimePrice);
                    Thread.sleep(200);
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
     * 做T交易
     */
    private void saleT(List<TradingRecord> todaySaleList, List<TradingRecord> todayBuyList, Momentum momentum,
                       StockRealTimePrice stockRealTimePrice) {
        for (TradingRecord tradingRecord : todayBuyList) {
            if (tradingRecord.getBuyPrice().multiply(momentum.getTRate()).doubleValue() >= stockRealTimePrice.getCurrentPrice() && CollectionUtils.isNotEmpty(todaySaleList)) {
                TradingRecord saleTrading = todaySaleList.get(0);
                if (saleTrading.getNumber() >= momentum.getAvailableNumber()) {
                    sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber());
                } else {
                    sale(momentum, stockRealTimePrice.getCurrentPrice(), saleTrading.getNumber());
                }
                tradingRecord.setProfit(new BigDecimal((stockRealTimePrice.getCurrentPrice() - tradingRecord.getBuyPrice().doubleValue()) * tradingRecord.getNumber().doubleValue()));
                tradingRecord.setClearance(true);
                tradingRecord.setSaleTime(new Date());
                tradingRecord.setClearanceReason(ClearanceReasonEnum.T.name());
                tradingRecordDao.updateById(tradingRecord);
                todaySaleList.remove(saleTrading);
                //momentum.setProfit(momentum.getProfit().add(tradingRecord.getProfit()));
            }
        }
    }

    /**
     * 止盈止损,返回是否需要中断后续买入操作
     */
    private void sale(Momentum momentum, StockRealTimePrice stockRealTimePrice) {
        if (momentum.getProfitRate().doubleValue() <= plankConfig.getStopLossRate()) {
            log.error("{} 触发止损,自动卖出", momentum.getName());
            sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber());
        }

        if (momentum.getFirstProfit().doubleValue() <= momentum.getProfitRate().doubleValue()) {
            log.error("{} 触发第一个减半止盈点,自动卖出一半可用持仓", momentum.getName());
            if (momentum.getAvailableNumber() * stockRealTimePrice.getCurrentPrice() < 30000) {
                sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber());
            } else {
                sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber() / 2);
            }
        }

        if (momentum.getSecondProfit().doubleValue() <= momentum.getProfitRate().doubleValue()) {
            log.error("{} 触发第二个减半止盈点,自动卖出一半可用持仓", momentum.getName());
            if (momentum.getAvailableNumber() * stockRealTimePrice.getCurrentPrice() < 30000) {
                sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber());
            } else {
                sale(momentum, stockRealTimePrice.getCurrentPrice(), momentum.getAvailableNumber() / 2);
            }
        }
    }

    private void sale(Momentum momentum, Double currentPrice, int amount) {
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
            momentum.setAvailableNumber(momentum.getAvailableNumber() - amount);
            momentum.setHoldNumber(momentum.getHoldNumber() - amount);
            //更新利润比
            double total = momentum.getHoldNumber().doubleValue() * currentPrice;
            //查询盈利比例
            momentum.setProfitRate(momentum.getProfit().divide(new BigDecimal(total), 2, RoundingMode.HALF_UP));
        } else {
            log.error("触发{}止盈、止损，交易失败!", momentum.getName());
        }
    }

    /**
     * 低吸买入
     */
    private void buy(Momentum momentum, StockRealTimePrice stockRealTimePrice) {
        if (momentum.getAuto()) {
            boolean check = false;
            if (momentum.getNe3() && stockRealTimePrice.getIncreaseRate() < -0.03) {
                check = true;
                momentum.setNe3(false);
            }
            if (momentum.getNe6() && stockRealTimePrice.getIncreaseRate() < -0.06) {
                check = true;
                momentum.setNe6(false);
            }
            if (momentum.getNe9() && stockRealTimePrice.getIncreaseRate() < -0.09) {
                check = true;
                momentum.setNe9(false);
            }

            if (check) {
                int sum = 0, amount = 1;
                while (sum <= plankConfig.getSingleTransactionLimitAmount()) {
                    sum = (int) (amount++ * 100 * stockRealTimePrice.getCurrentPrice());
                }
                amount -= 2;
                if (amount >= 1) {
                    double cost = amount * 100 * stockRealTimePrice.getCurrentPrice();
                    if (momentum.getTotal().intValue() + cost < plankConfig.getAutomaticTradingMoneyLimitUp()) {
                        buy(momentum, amount * 100, stockRealTimePrice.getCurrentPrice());
                    }
                }
            }
        }
    }

    /**
     * 东财挂单接口
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
            momentum.setHoldNumber(momentum.getHoldNumber() + amount);
        } else {
            log.error("下单[{}]失败,message:{}", momentum.getName(), response.getMessage());
        }
        return response.success();
    }
}
