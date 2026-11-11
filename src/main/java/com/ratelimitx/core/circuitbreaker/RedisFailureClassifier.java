package com.ratelimitx.core.circuitbreaker;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;

import com.ratelimitx.core.service.RateLimitScriptException;

/**
 * Decides whether an exception means "Redis is unreachable or too slow".
 *
 * <p>The breaker must react to that and to nothing else. Counting every exception made it
 * impossible to tell an outage from a bug: a {@code NullPointerException} in the limiter would
 * trip the circuit, hide itself behind the fallback, and leave the service quietly running on
 * per-instance limits with no outage to find.
 *
 * <p>The cause chain is walked because Spring wraps driver exceptions, and Lettuce wraps socket
 * failures.
 */
public final class RedisFailureClassifier {

    private static final Class<?>[] REDIS_FAILURES = {
            // Spring's translation of a Lettuce command timeout.
            QueryTimeoutException.class,
            // Connection refused, reset, or lost.
            RedisConnectionFailureException.class,
            // Superclass of the above; also covers a dead connection factory.
            DataAccessResourceFailureException.class,
            // Connection pool exhausted or unable to hand out a connection.
            PoolException.class,
            // Raw socket and timeout failures, when nothing wrapped them.
            SocketException.class,
            SocketTimeoutException.class,
            TimeoutException.class,
    };

    /**
     * Lettuce types, matched by name so this class does not depend on the driver being present at
     * compile time.
     */
    private static final String[] REDIS_FAILURE_CLASS_NAMES = {
            "io.lettuce.core.RedisConnectionException",
            "io.lettuce.core.RedisCommandTimeoutException",
            "io.lettuce.core.RedisException",
            "redis.clients.jedis.exceptions.JedisConnectionException",
    };

    private RedisFailureClassifier() {
    }

    /**
     * @return true if {@code throwable}, or anything in its cause chain, indicates that Redis could
     *         not be reached or did not answer in time
     */
    public static boolean isRedisFailure(Throwable throwable) {
        Throwable current = throwable;
        // Bounded so a self-referencing cause chain cannot spin forever.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof RateLimitScriptException) {
                // A script returning no result means the call did not complete.
                return true;
            }
            for (Class<?> type : REDIS_FAILURES) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
            String name = current.getClass().getName();
            for (String candidate : REDIS_FAILURE_CLASS_NAMES) {
                if (name.equals(candidate)) {
                    return true;
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
