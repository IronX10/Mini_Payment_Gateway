package com.dixansh.mini_payment_gateway.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the webhook retry backoff formula. It's pure, so it's tested directly with a fixed
 * schedule and a <b>seeded</b> {@link Random} - the jitter is randomised in production but fully
 * deterministic here, so we can assert exact values, not just ranges.
 */
class BackoffCalculatorTest {

    // The production default schedule (mpg.webhook.*): 60s base, x5 growth, capped at 1h.
    private static final long BASE = 60;
    private static final int MULT = 5;
    private static final long MAX = 3600;

    @ParameterizedTest(name = "attempt {0} -> capped {1}s")
    @CsvSource({
            "1, 60",     // 60 * 5^0
            "2, 300",    // 60 * 5^1
            "3, 1500",   // 60 * 5^2
            "4, 3600",   // 60 * 5^3 = 7500, clamped to the 3600 cap
            "5, 3600"    // stays pinned at the cap
    })
    void cappedSeconds_followsExponentialScheduleThenPins(int attempt, long expected) {
        assertThat(BackoffCalculator.cappedSeconds(attempt, BASE, MULT, MAX)).isEqualTo(expected);
    }

    @Test
    void cappedSeconds_neverExceedsMaxEvenForHugeAttempt() {
        // A large attempt number would overflow base * multiplier^(attempt-1) in naive long math;
        // the double-space clamp must pin it at the cap rather than wrap negative.
        assertThat(BackoffCalculator.cappedSeconds(50, BASE, MULT, MAX)).isEqualTo(MAX);
    }

    @Test
    void cappedSeconds_rejectsAttemptBelowOne() {
        assertThatThrownBy(() -> BackoffCalculator.cappedSeconds(0, BASE, MULT, MAX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalJitter_staysWithinHalfCapToCap_overManyDraws() {
        // Equal jitter guarantees delay in [cap/2, cap]. Assert the invariant across a range of
        // attempts and many random draws - this is the property that matters, per the roadmap
        // ("assert invariants, not exact interleavings").
        Random rng = new Random(1234567L);
        for (int attempt = 1; attempt <= 6; attempt++) {
            long cap = BackoffCalculator.cappedSeconds(attempt, BASE, MULT, MAX);
            for (int i = 0; i < 1000; i++) {
                long delay = BackoffCalculator.equalJitterSeconds(attempt, BASE, MULT, MAX, rng);
                assertThat(delay).isBetween(cap / 2, cap);
            }
        }
    }

    @Test
    void equalJitter_isDeterministicForAFixedSeed() {
        long a = BackoffCalculator.equalJitterSeconds(3, BASE, MULT, MAX, new Random(42));
        long b = BackoffCalculator.equalJitterSeconds(3, BASE, MULT, MAX, new Random(42));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalJitter_spreadsAcrossTheWindow_notAlwaysTheCap() {
        // Sanity check that jitter actually varies (defends against a "return cap" regression).
        Random rng = new Random(99);
        long cap = BackoffCalculator.cappedSeconds(3, BASE, MULT, MAX);
        boolean sawBelowCap = false;
        for (int i = 0; i < 200 && !sawBelowCap; i++) {
            if (BackoffCalculator.equalJitterSeconds(3, BASE, MULT, MAX, rng) < cap) {
                sawBelowCap = true;
            }
        }
        assertThat(sawBelowCap).isTrue();
    }
}
