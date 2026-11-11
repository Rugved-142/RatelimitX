package com.ratelimitx.core.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitConfig {

    public static final String FIXED_WINDOW = "fixed-window";
    public static final String TOKEN_BUCKET = "token-bucket";
    public static final String SLIDING_WINDOW = "sliding-window";

    /** The only accepted algorithm names. */
    public static final Set<String> SUPPORTED_ALGORITHMS =
            Set.of(FIXED_WINDOW, TOKEN_BUCKET, SLIDING_WINDOW);

    /**
     * The active algorithm.
     *
     * <p>Held in an {@link AtomicReference} because {@code POST /admin/algorithm} can replace it
     * while requests are in flight. A plain field would let a request thread keep reading a stale
     * value indefinitely, since nothing forces the write to become visible to it.
     */
    private final AtomicReference<String> algorithm = new AtomicReference<>(SLIDING_WINDOW);

    private int bucketCapacity = 10;
    private double refillRate = 1.0;

    private int maxRequests = 10;
    private int windowSizeSeconds = 60;

    /** Rejects an unusable value from configuration at startup rather than on first request. */
    @PostConstruct
    void validate() {
        requireSupported(algorithm.get());
        if (maxRequests <= 0) {
            throw new IllegalStateException("ratelimit.max-requests must be positive");
        }
        if (windowSizeSeconds <= 0) {
            throw new IllegalStateException("ratelimit.window-size-seconds must be positive");
        }
        if (bucketCapacity <= 0) {
            throw new IllegalStateException("ratelimit.bucket-capacity must be positive");
        }
    }

    /**
     * Validates an algorithm name.
     *
     * @return the normalised name
     * @throws UnsupportedAlgorithmException if the name is null, blank or not one of the three
     */
    public static String requireSupported(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new UnsupportedAlgorithmException(candidate, sortedNames());
        }
        String normalised = candidate.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ALGORITHMS.contains(normalised)) {
            throw new UnsupportedAlgorithmException(candidate, sortedNames());
        }
        return normalised;
    }

    /** Supported names in a stable order, for error messages and API responses. */
    public static List<String> sortedNames() {
        return SUPPORTED_ALGORITHMS.stream().sorted().toList();
    }

    public String getAlgorithm() {
        return algorithm.get();
    }

    /**
     * Sets the active algorithm, validating the name first.
     *
     * @return the previous algorithm
     * @throws UnsupportedAlgorithmException if the name is not one of the three
     */
    public String setAlgorithmChecked(String candidate) {
        return algorithm.getAndSet(requireSupported(candidate));
    }

    /** Property binding entry point; validation happens in {@link #validate()} at startup. */
    public void setAlgorithm(String algorithm) {
        this.algorithm.set(algorithm == null ? null : algorithm.trim().toLowerCase(Locale.ROOT));
    }

    public int getBucketCapacity() {
        return bucketCapacity;
    }

    public void setBucketCapacity(int bucketCapacity) {
        this.bucketCapacity = bucketCapacity;
    }

    public double getRefillRate() {
        return refillRate;
    }

    public void setRefillRate(double refillRate) {
        this.refillRate = refillRate;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    /** @return the window length in seconds. */
    public int getWindowSizeSeconds() {
        return windowSizeSeconds;
    }

    /** @param windowSizeSeconds window length in seconds. */
    public void setWindowSizeSeconds(int windowSizeSeconds) {
        this.windowSizeSeconds = windowSizeSeconds;
    }
}
