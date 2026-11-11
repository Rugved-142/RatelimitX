package com.ratelimitx.core.events;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings for the analytics event feed. */
@Component
@ConfigurationProperties(prefix = "ratelimit.events")
public class EventsProperties {

    /**
     * Whether decisions are published at all.
     *
     * <p>Off by default. While it is off nothing contacts Kafka, no background thread runs, and
     * the request path is byte for byte the same work it was before this feature existed.
     */
    private boolean enabled = false;

    /** Topic decisions are published to. */
    private String topic = "ratelimit-events";

    /**
     * How many events may wait to be sent.
     *
     * <p>The queue is the whole back-pressure story: when it is full the request thread drops the
     * event and increments a counter rather than waiting. A larger queue survives a longer Kafka
     * stall at the cost of more heap and staler events when it drains.
     */
    private int queueCapacity = 1000;

    /** How long the sending thread waits for one broker acknowledgement before giving up. */
    private Duration sendTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }
}
