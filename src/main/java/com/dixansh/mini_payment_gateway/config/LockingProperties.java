package com.dixansh.mini_payment_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for concurrency control (Phase 2). Pessimistic is the default because it wins under
 * the hot-account contention a payment system actually sees; the optimistic path is kept behind
 * this flag so both are demonstrable. Bind from {@code mpg.locking.*}.
 */
@Component
@ConfigurationProperties(prefix = "mpg.locking")
public class LockingProperties {

    public enum Strategy {
        /** SELECT ... FOR UPDATE on both rows, locked in ascending id order (no deadlocks). */
        PESSIMISTIC,
        /** @Version check on commit; retry the whole transfer on conflict. */
        OPTIMISTIC
    }

    private Strategy strategy = Strategy.PESSIMISTIC;

    /** Max attempts for the optimistic retry loop before giving up (409). Ignored when pessimistic. */
    private int maxRetries = 5;

    /** Base backoff between optimistic retries; actual sleep is randomized up to 2x for jitter. */
    private long baseBackoffMillis = 20;

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBaseBackoffMillis() {
        return baseBackoffMillis;
    }

    public void setBaseBackoffMillis(long baseBackoffMillis) {
        this.baseBackoffMillis = baseBackoffMillis;
    }
}
