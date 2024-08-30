package com.mistra.plank.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Mistra @ Version: 1.0
 * @ Time: 2021/11/18 22:09
 * @ Description: 配置文件
 * @ Copyright (c) Mistra,All Rights Reserved
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "plank")
public class PlankConfig {

    /**
     * 雪球 Cookie
     */
    private String xueQiuCookie;

    /**
     * 雪球 获取某只股票最近recentDayNumber天的每日涨跌记录url
     */
    private String xueQiuStockDetailUrl;

    /**
     * 雪球 获取某只股票当天实时价格,是否涨停等信息
     */
    private String xueQiuStockLimitUpPriceUrl;

    /**
     * 雪球 获取某只股票最近多少天的记录
     */
    private Integer recentDayNumber;

    /**
     * 打印日志时显示股票名称还是code
     */
    private Boolean printName;

    /**
     * 是否开启自动交易
     */
    private Boolean automaticTrading;

    /**
     * 止损比率
     */
    private Double stopLossRate;

    /**
     * 止盈比率
     */
    private Double takeProfitRate;

    /**
     * 止盈金额
     */
    private Double takeProfit;

    /**
     * 单只股票交易买入金额上限
     */
    private Double automaticTradingMoneyLimitUp;

    /**
     * 单笔交易金额上限
     */
    private Integer singleTransactionLimitAmount;

    /**
     * 是否开启持仓监控
     */
    private Boolean enableMonitor;

    /**
     * 更新股票池
     */
    private String updateAllStockUrl;
}