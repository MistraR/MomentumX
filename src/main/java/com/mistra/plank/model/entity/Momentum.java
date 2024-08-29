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
     * 是否开启闪崩低吸
     */
    @TableField(value = "flash_crash")
    private Boolean flashCrash;

    /**
     * 是否开启-3低吸
     */
    @TableField(value = "ne3")
    private Boolean ne3;

    /**
     * 是否开启-6低吸
     */
    @TableField(value = "ne6")
    private Boolean ne6;

    /**
     * 是否开启-9低吸
     */
    @TableField(value = "ne9")
    private Boolean ne9;

    /**
     * 做T盈利差额，默认3个点 1.03
     */
    @TableField(value = "t_rate")
    private BigDecimal tRate;

    /**
     * 第一个减半止盈点，默认5个点 1.05
     */
    @TableField(value = "first_profit")
    private BigDecimal firstProfit;

    /**
     * 第二个减半止盈点，默认13个点 1.13
     */
    @TableField(value = "second_profit")
    private BigDecimal secondProfit;

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
    @TableField(value = "total")
    private BigDecimal total;

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

}
