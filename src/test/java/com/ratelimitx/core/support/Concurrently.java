package com.ratelimitx.core.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs a check from many threads at once and counts how many succeeded. */
public final class Concurrently {

    private Concurrently() {
    }

    /**
     * Releases {@code threads} callers at the same instant and counts the ones returning true.
     *
     * <p>A latch is used rather than simply submitting tasks, so the threads contend instead of
     * trickling through a warming pool — without that the race under test rarely happens.
     *
     * @return how many invocations returned true
     */
    public static int countAllowed(int threads, Callable<Boolean> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 64));
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    go.await();
                    if (Boolean.TRUE.equals(attempt.call())) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            go.countDown();
            for (Future<?> future : futures) {
                // get() rethrows anything a worker threw, so a failure is not silently counted
                // as a denial.
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
        }
        return allowed.get();
    }
}
