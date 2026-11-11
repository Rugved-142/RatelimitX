package com.ratelimitx.core.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds events into hourly aggregates, exactly once per event id.
 *
 * <p>Both writes happen in one transaction: the {@code processed_events} row that claims the event
 * id and the aggregate it contributes to. A redelivery of an id already present changes nothing
 * and returns, so at-least-once delivery produces at-most-once counting.
 */
@Service
public class AnalyticsService {

    private final HourlyAggregateRepository aggregates;
    private final ProcessedEventRepository processedEvents;

    public AnalyticsService(HourlyAggregateRepository aggregates,
            ProcessedEventRepository processedEvents) {
        this.aggregates = aggregates;
        this.processedEvents = processedEvents;
    }

    /**
     * Counts one event, or does nothing if it has been counted before.
     *
     * @return true if this call counted the event, false if it was a duplicate
     */
    @Transactional
    public boolean record(RateLimitEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            return false;
        }
        processedEvents.save(new ProcessedEvent(event.eventId(), Instant.now()));

        Instant hour = event.timestamp().truncatedTo(ChronoUnit.HOURS);
        HourlyAggregate aggregate = aggregates
                .findByHourAndRoleAndAlgorithm(hour, event.role(), event.algorithm())
                .orElseGet(() -> new HourlyAggregate(hour, event.role(), event.algorithm()));
        aggregate.count(event.allowed());
        aggregates.save(aggregate);
        return true;
    }
}
