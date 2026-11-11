package com.ratelimitx.core.events;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An event id this consumer has already counted.
 *
 * <p>Kafka delivery is at-least-once, so the same event can arrive twice - after a rebalance, or
 * when an offset commit is lost. This table is what makes counting it twice impossible: the row is
 * written in the same transaction as the aggregate it contributed to, so either both happen or
 * neither does, and the primary key rejects the second attempt.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
