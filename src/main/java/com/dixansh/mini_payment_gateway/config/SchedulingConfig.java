package com.dixansh.mini_payment_gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} so the outbox relay and webhook-retry loops run. Kept as its own
 * config so scheduling can be excluded wholesale in slice tests that don't want background ticks.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
