package com.ratelimitx.core.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.service.MetricsService;



@RestController
@RequestMapping("/metrics")
@CrossOrigin(origins="*")
public class MetricsController {

    @Autowired
    private MetricsService metricsService;

    @GetMapping("/summary")
    public Map<String, Object> getSummary(){
        return metricsService.getMetricsSummary();
    }
    @GetMapping("/hourly")
    public Map<String, Object> getHourlyMetrics() {
        return metricsService.getCurrentHourMetrics();
    }

    @GetMapping("/daily")
    public Map<String, Object> getDailyMetrics() {
        return metricsService.getCurrentDayMetrics();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistoricalMetrics(
            @RequestParam(defaultValue = "24") int hours) {
        // Cap at 168 hours (7 days) to prevent huge queries
        return metricsService.getHistoricalMetrics(Math.min(hours, 168));
    }

    @GetMapping("/user/{userId}")
    public Map<String, Object> getUserMetrics(@PathVariable String userId) {
        return metricsService.getUserMetrics(userId);
    }
}
