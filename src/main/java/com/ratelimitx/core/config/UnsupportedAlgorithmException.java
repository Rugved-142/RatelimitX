package com.ratelimitx.core.config;

import java.util.List;

/**
 * Thrown when a caller asks for a rate limiting algorithm that does not exist. Mapped to 400 by
 * the global exception handler.
 */
public class UnsupportedAlgorithmException extends IllegalArgumentException {

    private final List<String> supported;

    public UnsupportedAlgorithmException(String requested, List<String> supported) {
        super("Unsupported algorithm: " + requested + ". Supported: " + String.join(", ", supported));
        this.supported = List.copyOf(supported);
    }

    public List<String> getSupported() {
        return supported;
    }
}
