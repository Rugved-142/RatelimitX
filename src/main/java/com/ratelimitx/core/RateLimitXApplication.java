package com.ratelimitx.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.ratelimitx.core.config.RateLimitConfig;


@SpringBootApplication
@EnableConfigurationProperties(RateLimitConfig.class)
public class RateLimitXApplication {

	public static void main(String[] args) {
		SpringApplication.run(RateLimitXApplication.class, args);
	}

}
