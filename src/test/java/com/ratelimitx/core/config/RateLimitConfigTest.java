package com.ratelimitx.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers runtime algorithm selection: validation and thread safety of the switch that backs
 * {@code POST /admin/algorithm}.
 */
class RateLimitConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"fixed-window", "token-bucket", "sliding-window"})
    void acceptsEverySupportedAlgorithm(String name) {
        RateLimitConfig config = new RateLimitConfig();
        config.setAlgorithmChecked(name);
        assertEquals(name, config.getAlgorithm());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIXED-WINDOW", "  Token-Bucket  ", "Sliding-Window"})
    void normalisesCaseAndSurroundingSpace(String name) {
        RateLimitConfig config = new RateLimitConfig();
        config.setAlgorithmChecked(name);
        assertEquals(name.trim().toLowerCase(), config.getAlgorithm());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "leaky-bucket", "sliding_window", "fixed window", "none"})
    void rejectsUnsupportedNames(String name) {
        RateLimitConfig config = new RateLimitConfig();
        assertThrows(UnsupportedAlgorithmException.class, () -> config.setAlgorithmChecked(name));
    }

    @Test
    void rejectsNull() {
        RateLimitConfig config = new RateLimitConfig();
        assertThrows(UnsupportedAlgorithmException.class, () -> config.setAlgorithmChecked(null));
    }

    @Test
    void leavesTheAlgorithmUnchangedWhenRejected() {
        RateLimitConfig config = new RateLimitConfig();
        config.setAlgorithmChecked("token-bucket");

        assertThrows(UnsupportedAlgorithmException.class,
                () -> config.setAlgorithmChecked("leaky-bucket"));

        assertEquals("token-bucket", config.getAlgorithm());
    }

    @Test
    void reportsThePreviousAlgorithm() {
        RateLimitConfig config = new RateLimitConfig();
        config.setAlgorithmChecked("fixed-window");
        assertEquals("fixed-window", config.setAlgorithmChecked("sliding-window"));
        assertEquals("sliding-window", config.getAlgorithm());
    }

    /**
     * Readers must never observe a value that was never set, and every write must become visible.
     * A plain non-volatile field passes this only by luck.
     */
    @Test
    void switchingUnderConcurrentReadsOnlyEverYieldsValidValues() throws Exception {
        RateLimitConfig config = new RateLimitConfig();
        String[] names = {"fixed-window", "token-bucket", "sliding-window"};

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        Set<String> observed = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                start.await();
                while (running.get()) {
                    observed.add(config.getAlgorithm());
                }
                return null;
            });
        }

        pool.submit(() -> {
            start.await();
            for (int i = 0; i < 20_000; i++) {
                config.setAlgorithmChecked(names[i % names.length]);
            }
            return null;
        });

        start.countDown();
        Thread.sleep(300);
        running.set(false);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not finish");

        assertTrue(RateLimitConfig.SUPPORTED_ALGORITHMS.containsAll(observed),
                "readers observed values outside the supported set: " + observed);

        // The final write must be visible to a fresh read.
        String last = names[(20_000 - 1) % names.length];
        assertEquals(last, config.getAlgorithm());
    }

    @Test
    void listsSupportedNamesInAStableOrder() {
        assertEquals(java.util.List.of("fixed-window", "sliding-window", "token-bucket"),
                RateLimitConfig.sortedNames());
    }
}
