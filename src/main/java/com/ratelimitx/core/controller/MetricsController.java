package com.ratelimitx.core.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.service.MetricsService;



/**
 * Reads the legacy Redis-backed counters.
 *
 * <p>Registered only when {@code metrics.legacy.enabled=true}, since it has nothing to read
 * otherwise. These paths return 404 by default; Prometheus metrics are unaffected.
 */
@RestController
@RequestMapping("/metrics")
@CrossOrigin(origins="*")
@ConditionalOnProperty(name = "metrics.legacy.enabled", havingValue = "true")
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
