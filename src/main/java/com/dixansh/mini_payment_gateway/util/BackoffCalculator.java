package com.dixansh.mini_payment_gateway.util;

import java.util.random.RandomGenerator;

/**
 * Pure, side-effect-free exponential backoff with "equal jitter" (AWS-style), hard-capped at a
 * maximum. Extracted from the webhook retry path so the formula can be unit-tested in isolation with
 * a seeded {@link RandomGenerator} - no clock, no Redis, no Spring.
 *
 * <p>The un-jittered schedule is {@code base * multiplier^(attempt-1)}, clamped to {@code max}. Equal
 * jitter then keeps half of that capped delay fixed and randomises the other half, so a burst of
 * simultaneous failures doesn't retry in lockstep (thundering herd) while retries never bunch up at
 * zero. Because the random half is drawn from the already-capped delay, the result always lands in
 * {@code [cap/2, cap]} and never exceeds {@code max}.
 */
public final class BackoffCalculator {

    private BackoffCalculator() {
    }

    /**
     * The deterministic (un-jittered) backoff for an attempt: {@code min(base * multiplier^(attempt-1), max)}.
     * The exponentiation is done in double and clamped before casting, so a large attempt number can
     * never overflow a long - it simply pins at {@code max}.
     *
     * @param attempt        1-based attempt number (attempt 1 = the first retry)
     * @param baseSeconds    delay for attempt 1, before jitter
     * @param multiplier     growth factor per attempt
     * @param maxSeconds     hard cap on any single delay
     * @return the capped delay in seconds, always in {@code [0, maxSeconds]}
     */
    public static long cappedSeconds(int attempt, long baseSeconds, int multiplier, long maxSeconds) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
        double factor = Math.pow(multiplier, attempt - 1);
        double raw = baseSeconds * factor;
        // Clamp in double space first: raw can exceed Long.MAX_VALUE for a big attempt, and casting an
        // out-of-range double to long saturates rather than wraps, but Math.min keeps it explicit.
        double capped = Math.min(raw, (double) maxSeconds);
        return (long) Math.max(0, capped);
    }

    /**
     * The capped delay with equal jitter applied: {@code half + rng.nextLong(half+1)} where
     * {@code half = cappedSeconds/2}. Result is always in {@code [cap/2, cap]} and never exceeds
     * {@code maxSeconds}.
     *
     * @param rng the randomness source; inject a seeded {@link java.util.Random} in tests,
     *            {@link java.util.concurrent.ThreadLocalRandom#current()} in production
     */
    public static long equalJitterSeconds(int attempt, long baseSeconds, int multiplier,
                                           long maxSeconds, RandomGenerator rng) {
        long capped = cappedSeconds(attempt, baseSeconds, multiplier, maxSeconds);
        long half = capped / 2;
        return half + rng.nextLong(half + 1);
    }
}
