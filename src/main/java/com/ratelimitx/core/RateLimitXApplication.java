package com.ratelimitx.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RateLimitX entry point.
 *
 * <p>The configuration property classes are plain {@code @Component}s, so they are picked up by
 * component scanning. Listing one in {@code @EnableConfigurationProperties} as well would register
 * it a second time, leaving two instances of the same settings in the context — which matters now
 * that the active algorithm can be switched at runtime, since a switch would reach only one of
 * them.
 */
@SpringBootApplication
public class RateLimitXApplication {

	public static void main(String[] args) {
		SpringApplication.run(RateLimitXApplication.class, args);
	}

}
