package com.mistra.plank.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mistra.plank.job.DailyRecordProcessor;
import com.mistra.plank.model.param.AutoTradeParam;
import com.mistra.plank.model.param.SelfSelectParam;
import com.mistra.plank.service.StockSelectedService;

/**
 * @author mistra@future.com
 * @date 2021/11/19
 */
@RestController
public class PlankController {

    private final StockSelectedService stockSelectedService;
    private final DailyRecordProcessor dailyRecordProcessor;

    public PlankController(StockSelectedService stockSelectedService, DailyRecordProcessor dailyRecordProcessor) {
        this.stockSelectedService = stockSelectedService;
        this.dailyRecordProcessor = dailyRecordProcessor;
    }

    /**
     * 编辑web页面自选
     *
     * @param selfSelectParam SelfSelectParam
     */
    @PostMapping("/add-self-select")
    public void addSelfSelect(@RequestBody SelfSelectParam selfSelectParam) {
        stockSelectedService.addSelfSelect(selfSelectParam);
    }

    /**
     * 下一个交易日自动交易池
     *
     * @param autoTradeParams autoTradeParams
     */
    @PostMapping("/tomorrow-auto-trade-pool")
    public void tomorrowAutoTradePool(@RequestBody List<AutoTradeParam> autoTradeParams) {
        stockSelectedService.tomorrowAutoTradePool(autoTradeParams);
    }

    /**
     * 更新某支股票最近recentDayNumber天的交易数据
     */
    @PostMapping("/update-dailyRecord-byCode")
    public void updateByName(@RequestParam String name, @RequestParam Integer recentDayNumber) {
        dailyRecordProcessor.updateByName(name, recentDayNumber);
    }

}
