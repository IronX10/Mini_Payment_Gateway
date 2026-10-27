package com.dixansh.mini_payment_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for webhook delivery + retry. Bound from {@code mpg.webhook.*}.
 */
@Component
@ConfigurationProperties(prefix = "mpg.webhook")
public class WebhookProperties {

    /** Give up (mark DEAD) once {@code attempt} reaches this. */
    private int maxAttempts = 6;

    /** Backoff base: delay grows as base * multiplier^(attempt-1), plus jitter. */
    private long baseBackoffSeconds = 60;
    private int backoffMultiplier = 5;

    /** Cap on any single backoff, so late retries don't drift days out. */
    private long maxBackoffSeconds = 3600;

    /** How often the retry scheduler scans for due deliveries, in milliseconds. */
    private long retryPollMs = 1000;

    /** Max due deliveries claimed per retry tick. */
    private int retryBatchSize = 50;

    private long connectTimeoutMs = 2000;
    private long readTimeoutMs = 3000;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBaseBackoffSeconds() {
        return baseBackoffSeconds;
    }

    public void setBaseBackoffSeconds(long baseBackoffSeconds) {
        this.baseBackoffSeconds = baseBackoffSeconds;
    }

    public int getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(int backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(long maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public long getRetryPollMs() {
        return retryPollMs;
    }

    public void setRetryPollMs(long retryPollMs) {
        this.retryPollMs = retryPollMs;
    }

    public int getRetryBatchSize() {
        return retryBatchSize;
    }

    public void setRetryBatchSize(int retryBatchSize) {
        this.retryBatchSize = retryBatchSize;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
