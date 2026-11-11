package com.ratelimitx.core.events;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Decisions for one hour, role and algorithm, counted.
 *
 * <p>The natural key is (hour, role, algorithm) and the unique constraint enforces it; the
 * surrogate id exists only to keep the mapping plain. {@code hour} is the event timestamp
 * truncated to the hour, in UTC.
 */
@Entity
@Table(name = "analytics_hourly_aggregate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_hour_role_algorithm",
                columnNames = {"hour", "role", "algorithm"}))
public class HourlyAggregate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hour", nullable = false)
    private Instant hour;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "allowed", nullable = false)
    private long allowed;

    @Column(name = "denied", nullable = false)
    private long denied;

    protected HourlyAggregate() {
        // for JPA
    }

    public HourlyAggregate(Instant hour, String role, String algorithm) {
        this.hour = hour;
        this.role = role;
        this.algorithm = algorithm;
    }

    public void count(boolean wasAllowed) {
        if (wasAllowed) {
            allowed++;
        } else {
            denied++;
        }
    }

    public Long getId() {
        return id;
    }

    public Instant getHour() {
        return hour;
    }

    public String getRole() {
        return role;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public long getAllowed() {
        return allowed;
    }

    public long getDenied() {
        return denied;
    }
}
