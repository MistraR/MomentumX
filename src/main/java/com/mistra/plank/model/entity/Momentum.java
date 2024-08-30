package com.mistra.plank.model.entity;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @ author: rui.wang@yamu.com
 * @ description:
 * @ date: 2024/8/29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "momentum", autoResultMap = true)
public class Momentum {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;

    @TableField(value = "name")
    private String name;

    /**
     * 是否开启自动套利交易
     */
    @TableField(value = "auto")
    private Boolean auto;

    /**
     * 第一个止盈点，默认5个点 5
     */
    @TableField(value = "first_profit_sale")
    private BigDecimal firstProfitSale;

    /**
     * 第二个止盈点，默认10个点 10
     */
    @TableField(value = "second_profit_sale")
    private BigDecimal secondProfitSale;

    /**
     * 持仓数量
     */
    @TableField(value = "hold_number")
    private Integer holdNumber;

    /**
     * 可用数量
     */
    @TableField(value = "available_number")
    private Integer availableNumber;

    /**
     * 持仓总额
     */
    @TableField(value = "total_money")
    private BigDecimal totalMoney;

    /**
     * 利润总额
     */
    @TableField(value = "profit")
    private BigDecimal profit;

    /**
     * 盈亏比
     */
    @TableField(value = "profit_rate")
    private BigDecimal profitRate;

    /**
     * 成本价
     */
    @TableField(value = "cost_price")
    private BigDecimal costPrice;

    /**
     * 持仓金额上限
     */
    @TableField(value = "cost_limit")
    private BigDecimal costLimit;

    /**
     * 今日是否触板
     */
    @TableField(value = "today_plank")
    private Boolean todayPlank;

    /**
     * 是否可以买入
     */
    @TableField(value = "buy")
    private Boolean buy;

    /**
     * 默认止盈清仓金额
     */
    @TableField(value = "profit_limit")
    private BigDecimal profitLimit;

    /**
     * 做T波动差，默认3个点 1.03
     */
    @TableField(value = "t_rate")
    private BigDecimal tRate;

    /**
     * 第1个低吸点,默认-3
     */
    @TableField(value = "first_suck_rate")
    private BigDecimal firstSuckRate;

    /**
     * 第2个低吸点,默认-6
     */
    @TableField(value = "second_suck_rate")
    private BigDecimal secondSuckRate;

    /**
     * 第3个低吸点,默认-9
     */
    @TableField(value = "third_suck_rate")
    private BigDecimal thirdSuckRate;

    /**
     * 特定金额低吸
     */
    @TableField(value = "special_suck_price")
    private BigDecimal specialSuckPrice;

    /**
     * 是否开启闪崩低吸
     */
    @TableField(value = "flash_crash")
    private Boolean flashCrash;

    /**
     * 是否采用波动做T
     */
    @TableField(value = "wave_trading")
    private Boolean waveTrading;

    /**
     * 下一个低吸价格
     */
    @TableField(value = "next_suck_price")
    private BigDecimal nextSuckPrice;

    /**
     * 下一个卖出价格
     */
    @TableField(value = "next_sale_price")
    private BigDecimal nextSalePrice;

}
