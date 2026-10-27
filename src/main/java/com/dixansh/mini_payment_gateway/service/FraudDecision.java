package com.dixansh.mini_payment_gateway.service;

import java.util.List;

/**
 * Outcome of scoring one payment through the fraud rules engine: the total score, the rule codes
 * that fired (for explainability / audit), and whether that total crossed the hold threshold.
 *
 * <p>Immutable and self-contained so it can cross the scoring thread boundary safely (scoring runs
 * on a bounded-time worker; see {@code FraudService}).
 */
public record FraudDecision(boolean hold, int score, List<String> reasons) {

    /** The default-allow decision: used when scoring is disabled, times out, or errors (fail-open). */
    public static FraudDecision allow() {
        return new FraudDecision(false, 0, List.of());
    }

    /** Reasons as the comma-separated string persisted on the transaction ({@code null} if none). */
    public String reasonsCsv() {
        return reasons.isEmpty() ? null : String.join(",", reasons);
    }
}
