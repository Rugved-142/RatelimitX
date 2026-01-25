package com.ratelimitx.core.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;





@Service
public class MetricsService {

    @Autowired
    StringRedisTemplate redis;

    private static final String METRICS_PREFIX = "metrics:";

    private static final String TOTAL_REQUESTS = "total_requests";
    private static final String ALLOWED_REQUESTS = "allowed_requests";
    private static final String DENIED_REQUESTS = "denied_requests";
    private static final String RESPONSE_TIME_SUM = "response_time_sum";
    private static final String RESPONSE_TIME_COUNT = "response_time_count";

    public void recordRequest(String userId, boolean allowed, long responseTime) {

        String hourKey = getHourKey();
        String dayKey = getDayKey();

        incrementMetric(hourKey,TOTAL_REQUESTS);
        incrementMetric(dayKey, TOTAL_REQUESTS);


        if(allowed){
            incrementMetric(hourKey, ALLOWED_REQUESTS);
            incrementMetric(dayKey, ALLOWED_REQUESTS);
        }else{
            incrementMetric(hourKey, DENIED_REQUESTS);
            incrementMetric(dayKey, DENIED_REQUESTS);
        }

        incrementMetricBy(hourKey, RESPONSE_TIME_SUM, responseTime);
        incrementMetric(hourKey, RESPONSE_TIME_COUNT);
        incrementMetricBy(dayKey, RESPONSE_TIME_SUM, responseTime);
        incrementMetric(dayKey, RESPONSE_TIME_COUNT);


        String userDayKey = getDayKey() + ":user:" + userId;
        incrementMetric(userDayKey, TOTAL_REQUESTS);
        if(allowed){
            incrementMetric(userDayKey, ALLOWED_REQUESTS);
        }else{
            incrementMetric(userDayKey, DENIED_REQUESTS);
        }


        redis.expire(METRICS_PREFIX + hourKey, 48, TimeUnit.HOURS);
        redis.expire(METRICS_PREFIX + dayKey, 7,TimeUnit.DAYS);
        redis.expire(METRICS_PREFIX + userDayKey, 7, TimeUnit.DAYS);
    }


    public Map<String,Object> getMetricsSummary(){
        Map<String, Object> summary = new HashMap<>();

        Map<String, Object> hourly = getCurrentHourMetrics();
        Map<String, Object> daily = getCurrentDayMetrics();

        summary.put("currentHour", hourly);
        summary.put("currentDay",daily);

        long hourlyTotal = (long) hourly.getOrDefault("totalRequests",0L);
        long hourlyDenied = (long) hourly.getOrDefault("deniedRequests", 0L);

        double denialRate = hourlyTotal > 0 ? (double) hourlyDenied / hourlyTotal * 100 : 0;
        summary.put("hourlyDenialRate", String.format("%.2f%%", denialRate));

        int minuteIntoHour = Instant.now().atZone(ZoneId.systemDefault()).getMinute();
        if(minuteIntoHour == 0) minuteIntoHour = 1;
        double rpm = (double) hourlyTotal / minuteIntoHour;
        summary.put("requestsPerMinute", String.format("%.2f", rpm));
        return summary;
    }

    public Map<String, Object> getCurrentHourMetrics() {
        return getMetricsForKey(getHourKey());
    }
    public Map<String, Object> getCurrentDayMetrics() {
        return getMetricsForKey(getDayKey());
    }

    public Map<String, Object> getUserMetrics(String userId){
        String userDayKey = getDayKey() + ":user:" + userId;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("userId",userId);
        metrics.put("today",getMetricsForKey(userDayKey));

        return metrics;
    }

    public Map<String, Object> getHistoricalMetrics(int hours){
        Map<String, Object> historical = new HashMap<>();
        Instant now = Instant.now();

        long totalRequests = 0;
        long totalAllowed = 0;
        long totalDenied = 0;

        // Loop through each hour
        for (int i = 0; i < hours; i++) {
            Instant hourTime = now.minus(i, ChronoUnit.HOURS);
            String hourKey = formatHourKey(hourTime);
            Map<String, Object> hourMetrics = getMetricsForKey(hourKey);

            // Accumulate totals
            totalRequests += (Long) hourMetrics.getOrDefault("totalRequests", 0L);
            totalAllowed += (Long) hourMetrics.getOrDefault("allowedRequests", 0L);
            totalDenied += (Long) hourMetrics.getOrDefault("deniedRequests", 0L);

            historical.put("hour_minus_" + i, hourMetrics);
        }

        // Add summary
        Map<String, Object> summaryData = new HashMap<>();
        summaryData.put("totalRequests", totalRequests);
        summaryData.put("totalAllowed", totalAllowed);
        summaryData.put("totalDenied", totalDenied);
        summaryData.put("hoursAnalyzed", hours);

        if (totalRequests > 0) {
            double successRate = (double) totalAllowed / totalRequests * 100;
            summaryData.put("successRate", String.format("%.2f%%", successRate));
        }

        historical.put("summary", summaryData);

        return historical;
    }
    private void incrementMetric(String timeKey, String metric) {
        String key = METRICS_PREFIX + timeKey;
        redis.opsForHash().increment(key, metric, 1);
    }

    private void incrementMetricBy(String timeKey, String metric, long value) {
        String key = METRICS_PREFIX + timeKey;
        redis.opsForHash().increment(key, metric, value);
    }



    private Map<String, Object> getMetricsForKey(String timeKey) {
        String key = METRICS_PREFIX + timeKey;

        // Read all fields from Redis hash
        Map<Object, Object> rawMetrics = redis.opsForHash().entries(key);

        Map<String, Object> metrics = new HashMap<>();

        // Parse raw values
        long total = parseLong(rawMetrics.get(TOTAL_REQUESTS));
        long allowed = parseLong(rawMetrics.get(ALLOWED_REQUESTS));
        long denied = parseLong(rawMetrics.get(DENIED_REQUESTS));
        long responseTimeSum = parseLong(rawMetrics.get(RESPONSE_TIME_SUM));
        long responseTimeCount = parseLong(rawMetrics.get(RESPONSE_TIME_COUNT));

        // Store basic counts
        metrics.put("totalRequests", total);
        metrics.put("allowedRequests", allowed);
        metrics.put("deniedRequests", denied);

        // Calculate average response time
        double avgResponseTime = responseTimeCount > 0
            ? (double) responseTimeSum / responseTimeCount
            : 0;
        metrics.put("avgResponseTimeMs", String.format("%.2f", avgResponseTime));

        // Calculate success rate
        double successRate = total > 0
            ? (double) allowed / total * 100
            : 100;
        metrics.put("successRate", String.format("%.2f%%", successRate));

        metrics.put("timeKey", timeKey);

        return metrics;
    }


    private long parseLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Long) return (Long) value;
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String getHourKey() {
        return formatHourKey(Instant.now());
    }
    private String formatHourKey(Instant time) {
        return "hourly:" + time.truncatedTo(ChronoUnit.HOURS)
                .toString()
                .replace(":", "-")
                .replace("T", "_");
    }
    private String getDayKey() {
        return "daily:" + Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .toString()
                .split("T")[0];
    }
}
