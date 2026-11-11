package com.ratelimitx.core.events;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the hourly aggregates.
 *
 * <p>Under {@code /admin}, so the filter chain's {@code hasRole("ADMIN")} rule covers it without
 * anything further here. Returns an empty list rather than a 404 when the feed has never run, so
 * a caller can tell "analytics is off" from "this endpoint does not exist".
 */
@RestController
@RequestMapping("/admin/analytics")
public class AnalyticsController {

    private final HourlyAggregateRepository aggregates;

    public AnalyticsController(HourlyAggregateRepository aggregates) {
        this.aggregates = aggregates;
    }

    @GetMapping
    public List<HourlyAggregateView> aggregates() {
        return aggregates.findAllByOrderByHourDescRoleAscAlgorithmAsc().stream()
                .map(a -> new HourlyAggregateView(a.getHour(), a.getRole(), a.getAlgorithm(),
                        a.getAllowed(), a.getDenied()))
                .toList();
    }

    /** The aggregate as served; keeps the entity's surrogate id out of the response. */
    public record HourlyAggregateView(
            Instant hour,
            String role,
            String algorithm,
            long allowed,
            long denied) {
    }
}
