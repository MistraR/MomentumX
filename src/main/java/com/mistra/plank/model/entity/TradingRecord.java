package com.mistra.plank.model.entity;

import java.math.BigDecimal;
import java.util.Date;
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
@TableName(value = "trading_record", autoResultMap = true)
public class TradingRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "code")
    private String code;

    @TableField(value = "name")
    private String name;

    /**
     * 买入数量
     */
    @TableField(value = "number")
    private Integer number;

    /**
     * 建仓价
     */
    @TableField(value = "buy_price")
    private BigDecimal buyPrice;

    /**
     * 利润总额
     */
    @TableField(value = "profit")
    private BigDecimal profit;

    /**
     * 买入时间
     */
    @TableField(value = "buy_time")
    private Date buyTime;

    /**
     * 自动交易类型
     */
    @TableField(value = "automatic_trading_type")
    private String automaticTradingType;

    /**
     * 清仓原因
     */
    @TableField(value = "clearance_reason")
    private String clearanceReason;

    /**
     * 是否清仓
     */
    @TableField(value = "clearance")
    private Boolean clearance;

    /**
     * 买入时间
     */
    @TableField(value = "sale_time")
    private Date saleTime;

    /**
     * 卖出价
     */
    @TableField(value = "sale_price")
    private BigDecimal salePrice;

}
