package com.dixansh.mini_payment_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the Phase 5 fraud rules engine. Bound from {@code mpg.fraud.*}.
 *
 * <p>The engine is a weighted sum of deterministic rules: each rule that fires contributes its
 * weight, and if the total reaches {@link #scoreThreshold} the payment is <b>HELD</b> for manual
 * review instead of settling. Everything here is configurable so the sensitivity can be tuned per
 * environment without a redeploy - and so the rules are trivially unit-testable with known inputs.
 *
 * <p>Default weights are chosen so that a clear burst (velocity) alone crosses the threshold, while
 * softer signals only hold when they <em>corroborate</em> each other (e.g. a large payment to a
 * brand-new payee). See {@code FraudService} for the rules themselves.
 */
@Component
@ConfigurationProperties(prefix = "mpg.fraud")
public class FraudProperties {

    /** Master switch. When false, scoring is skipped entirely and every payment settles normally. */
    private boolean enabled = true;

    /** Total score at or above which a payment is held for review. */
    private int scoreThreshold = 50;

    /**
     * Strict wall-clock budget for scoring inside the payment path. If scoring can't finish within
     * this many milliseconds we <b>default-allow</b> (let the payment through) and log - risk must
     * never add unbounded latency to a payment.
     */
    private long budgetMillis = 50;

    // --- Rule: velocity (Redis counter from Phase 4) ---
    /** Fire the velocity rule when the account already has &gt;= this many payments in the current minute. */
    private long velocityThreshold = 10;
    private int weightVelocity = 50;

    // --- Rule: amount over the account's 30-day P95 ---
    /** Look-back window for the per-account amount baseline. */
    private int p95LookbackDays = 30;
    /** Don't apply the P95 rule until the account has at least this many prior payments (avoids flagging new accounts). */
    private long p95MinSamples = 20;
    private int weightAmountOverP95 = 40;

    // --- Rule: first payment ever to this destination ---
    private int weightFirstTimePayee = 15;

    // --- Rule: round amount during midnight hours (both must hold) ---
    /** An amount is "round" when it is a positive whole multiple of this (e.g. 1000, 5000, 10000). */
    private long roundAmountMultiple = 1000;
    /** Midnight window as [start, end) UTC hours - the classic "when nobody's watching" signal. */
    private int midnightStartHour = 0;
    private int midnightEndHour = 5;
    private int weightRoundMidnight = 25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(int scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public long getBudgetMillis() {
        return budgetMillis;
    }

    public void setBudgetMillis(long budgetMillis) {
        this.budgetMillis = budgetMillis;
    }

    public long getVelocityThreshold() {
        return velocityThreshold;
    }

    public void setVelocityThreshold(long velocityThreshold) {
        this.velocityThreshold = velocityThreshold;
    }

    public int getWeightVelocity() {
        return weightVelocity;
    }

    public void setWeightVelocity(int weightVelocity) {
        this.weightVelocity = weightVelocity;
    }

    public int getP95LookbackDays() {
        return p95LookbackDays;
    }

    public void setP95LookbackDays(int p95LookbackDays) {
        this.p95LookbackDays = p95LookbackDays;
    }

    public long getP95MinSamples() {
        return p95MinSamples;
    }

    public void setP95MinSamples(long p95MinSamples) {
        this.p95MinSamples = p95MinSamples;
    }

    public int getWeightAmountOverP95() {
        return weightAmountOverP95;
    }

    public void setWeightAmountOverP95(int weightAmountOverP95) {
        this.weightAmountOverP95 = weightAmountOverP95;
    }

    public int getWeightFirstTimePayee() {
        return weightFirstTimePayee;
    }

    public void setWeightFirstTimePayee(int weightFirstTimePayee) {
        this.weightFirstTimePayee = weightFirstTimePayee;
    }

    public long getRoundAmountMultiple() {
        return roundAmountMultiple;
    }

    public void setRoundAmountMultiple(long roundAmountMultiple) {
        this.roundAmountMultiple = roundAmountMultiple;
    }

    public int getMidnightStartHour() {
        return midnightStartHour;
    }

    public void setMidnightStartHour(int midnightStartHour) {
        this.midnightStartHour = midnightStartHour;
    }

    public int getMidnightEndHour() {
        return midnightEndHour;
    }

    public void setMidnightEndHour(int midnightEndHour) {
        this.midnightEndHour = midnightEndHour;
    }

    public int getWeightRoundMidnight() {
        return weightRoundMidnight;
    }

    public void setWeightRoundMidnight(int weightRoundMidnight) {
        this.weightRoundMidnight = weightRoundMidnight;
    }
}
