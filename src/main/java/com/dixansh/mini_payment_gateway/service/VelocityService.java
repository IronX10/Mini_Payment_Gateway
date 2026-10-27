package com.dixansh.mini_payment_gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-account velocity counters in Redis: how many payments an account has initiated in the current
 * minute. Implemented as the classic {@code INCR tx_count:{account}:{minute}} + {@code EXPIRE 60}
 * fixed-window counter - cheap, atomic, and self-expiring. Built in Phase 4; the fraud engine in
 * Phase 5 reads it as its "too many payments in a short window" signal.
 *
 * <p>Every call is <b>fail-open</b>: a Redis blip must never block or fail a payment, so on error we
 * log and behave as if the count were zero. Velocity is a risk hint, not a correctness guarantee.
 */
@Service
public class VelocityService {

    private static final Logger log = LoggerFactory.getLogger(VelocityService.class);
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public VelocityService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records one payment for {@code accountId} in the current minute bucket and returns the running
     * count for that minute (1 on the first call). Sets the 60s TTL only on the first increment, so
     * the key that created the window owns its expiry.
     */
    public long recordAndCount(Long accountId) {
        String key = key(accountId, Instant.now());
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
            return count == null ? 0L : count;
        } catch (Exception redisUnavailable) {
            log.warn("velocity increment failed for account {}: {}", accountId, redisUnavailable.getMessage());
            return 0L;
        }
    }

    /** Current-minute payment count for {@code accountId} without incrementing; 0 if none/unavailable. */
    public long currentCount(Long accountId) {
        String key = key(accountId, Instant.now());
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception redisUnavailable) {
            log.warn("velocity read failed for account {}: {}", accountId, redisUnavailable.getMessage());
            return 0L;
        }
    }

    private static String key(Long accountId, Instant now) {
        long epochMinute = now.getEpochSecond() / 60;
        return "tx_count:" + accountId + ":" + epochMinute;
    }
}
