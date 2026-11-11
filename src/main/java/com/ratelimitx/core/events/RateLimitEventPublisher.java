package com.ratelimitx.core.events;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Hands decisions to Kafka without ever making a request wait for it.
 *
 * <p>The request thread only offers the event to a bounded queue and returns. One background
 * thread drains the queue and does the sending, so a slow or unreachable broker shows up as
 * dropped events rather than as latency on {@code /api/data}. That is the entire point of this
 * class: analytics must not be able to hurt the thing it measures.
 *
 * <p>Events are dropped, never buffered to disk and never retried indefinitely. Two things cause
 * a drop and each has its own counter: a full queue ({@code ratelimit.events.dropped}) and a send
 * that failed or timed out ({@code ratelimit.events.failed}).
 *
 * <p>The thread hands each event to the producer and does not wait for the acknowledgement; the
 * counters are incremented from the producer's own callback. Waiting per event made this thread
 * the bottleneck instead of the broker: one blocking round trip each, and with a 20ms linger
 * every one of them waited for a batch that a single in-flight record could never fill. Measured
 * at roughly 119 requests a second, that lost well over half the events to a queue that could not
 * drain. Not waiting lets the producer batch, which is what it is for.
 */
@Component
public class RateLimitEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitEventPublisher.class);

    /** How long the drain loop waits for an event before checking whether it should stop. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final KafkaTemplate<String, RateLimitEvent> kafkaTemplate;
    private final EventsProperties properties;
    private final BlockingQueue<RateLimitEvent> queue;
    private final Counter published;
    private final Counter dropped;
    private final Counter failed;

    private volatile boolean running;
    private Thread worker;

    public RateLimitEventPublisher(KafkaTemplate<String, RateLimitEvent> kafkaTemplate,
            EventsProperties properties,
            MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        this.published = Counter.builder("ratelimit.events.published")
                .description("Decisions sent to Kafka").register(registry);
        this.dropped = Counter.builder("ratelimit.events.dropped")
                .description("Decisions discarded because the queue was full").register(registry);
        this.failed = Counter.builder("ratelimit.events.failed")
                .description("Decisions discarded because the send failed").register(registry);
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            return;
        }
        running = true;
        worker = new Thread(this::drain, "ratelimit-events");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Queues one decision, or drops it.
     *
     * <p>Safe to call from a request thread: it never blocks, never throws, and returns
     * immediately when the feed is disabled.
     */
    public void publish(RateLimitEvent event) {
        if (!running) {
            return;
        }
        if (!queue.offer(event)) {
            dropped.increment();
        }
    }

    /** Visible for tests: how many events are waiting to be sent. */
    public int queueDepth() {
        return queue.size();
    }

    /** Visible for tests: whether this publisher started a sending thread. */
    public boolean isRunning() {
        return running;
    }

    private void drain() {
        while (running) {
            try {
                RateLimitEvent event = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    send(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void send(RateLimitEvent event) {
        try {
            kafkaTemplate.send(properties.getTopic(), event.userId(), event)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            published.increment();
                        } else {
                            // Best effort by design: count it, say so at debug, move on.
                            failed.increment();
                            logger.debug("Dropped analytics event {}: {}",
                                    event.eventId(), failure.toString());
                        }
                    });
        } catch (Exception e) {
            // send() itself throws when it cannot get metadata within max.block.ms.
            failed.increment();
            logger.debug("Could not hand analytics event {} to the producer: {}",
                    event.eventId(), e.toString());
        }
    }
}
