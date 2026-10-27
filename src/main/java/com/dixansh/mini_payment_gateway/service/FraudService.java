package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.FraudProperties;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Phase 5 fraud rules engine. Scores a payment <b>before it settles</b> against a set of
 * deterministic rules, each contributing its configured weight; if the total reaches the threshold
 * the caller holds the payment (status {@code HELD}) for manual review instead of settling it.
 *
 * <h2>Rules (each independently testable)</h2>
 * <ul>
 *   <li><b>AMOUNT_OVER_P95</b> - the amount exceeds this account's 30-day 95th-percentile payment,
 *       once the account has enough history to have a meaningful baseline.</li>
 *   <li><b>VELOCITY</b> - the account has already made more than N payments in the current minute
 *       (the Redis counter from Phase 4). A clear burst; weighted to hold on its own by default.</li>
 *   <li><b>FIRST_TIME_PAYEE</b> - the account has never successfully paid this destination before.</li>
 *   <li><b>ROUND_AMOUNT_MIDNIGHT</b> - a suspiciously round amount sent during the small hours
 *       (both must hold): a weak signal on its own, corroborating in combination.</li>
 * </ul>
 *
 * <h2>Latency budget &amp; fail-open</h2>
 * Scoring is <b>synchronous</b> on the payment path but capped at a strict wall-clock budget
 * ({@code mpg.fraud.budget-millis}, default 50ms). The work runs on a small worker pool and the
 * caller waits at most the budget; on timeout - or any error - we <b>default-allow</b> and log.
 * Rationale (the interview point): risk scoring must never add unbounded latency to, or become a
 * new failure mode for, taking a payment. We would rather occasionally let a risky payment through
 * (and catch it out-of-band) than fail or stall a good one. Availability of the money path wins.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    // Stable rule codes, persisted on the held transaction so a decision is always explainable.
    static final String RULE_AMOUNT_OVER_P95 = "AMOUNT_OVER_P95";
    static final String RULE_VELOCITY = "VELOCITY";
    static final String RULE_FIRST_TIME_PAYEE = "FIRST_TIME_PAYEE";
    static final String RULE_ROUND_AMOUNT_MIDNIGHT = "ROUND_AMOUNT_MIDNIGHT";

    private final FraudProperties properties;
    private final TransactionRepository transactionRepository;
    private final VelocityService velocityService;
    private final ExecutorService scoringPool;

    public FraudService(FraudProperties properties,
                        TransactionRepository transactionRepository,
                        VelocityService velocityService) {
        this.properties = properties;
        this.transactionRepository = transactionRepository;
        this.velocityService = velocityService;
        this.scoringPool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()), namedDaemonFactory());
    }

    /**
     * Score a payment and decide whether to hold it. Never throws and never blocks longer than the
     * configured budget: on timeout or error it returns {@link FraudDecision#allow()} (fail-open).
     */
    public FraudDecision score(Long source, Long dest, BigDecimal amount) {
        if (!properties.isEnabled()) {
            return FraudDecision.allow();
        }
        Future<FraudDecision> future = scoringPool.submit(() -> evaluate(source, dest, amount));
        try {
            return future.get(properties.getBudgetMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true); // stop the orphaned work; its result would be discarded anyway
            log.warn("fraud scoring exceeded {}ms budget for {}->{}, defaulting to allow",
                    properties.getBudgetMillis(), source, dest);
            return FraudDecision.allow();
        } catch (Exception e) {
            future.cancel(true);
            Thread.currentThread().interrupt(); // in case we were interrupted while waiting
            log.warn("fraud scoring failed for {}->{}, defaulting to allow: {}", source, dest, e.getMessage());
            return FraudDecision.allow();
        }
    }

    /** Runs all rules and sums their weights. Executed on the scoring worker under the time budget. */
    private FraudDecision evaluate(Long source, Long dest, BigDecimal amount) {
        int score = 0;
        List<String> reasons = new ArrayList<>(4);

        if (velocityRuleFires(source)) {
            score += properties.getWeightVelocity();
            reasons.add(RULE_VELOCITY);
        }
        if (amountOverP95(source, amount)) {
            score += properties.getWeightAmountOverP95();
            reasons.add(RULE_AMOUNT_OVER_P95);
        }
        if (firstTimePayee(source, dest)) {
            score += properties.getWeightFirstTimePayee();
            reasons.add(RULE_FIRST_TIME_PAYEE);
        }
        if (roundAmountAtMidnight(amount, Instant.now())) {
            score += properties.getWeightRoundMidnight();
            reasons.add(RULE_ROUND_AMOUNT_MIDNIGHT);
        }

        boolean hold = score >= properties.getScoreThreshold();
        return new FraudDecision(hold, score, reasons);
    }

    private boolean velocityRuleFires(Long source) {
        // currentCount is a non-incrementing read of payments already made this minute; the current
        // payment isn't counted yet (the caller records it after the decision).
        return velocityService.currentCount(source) >= properties.getVelocityThreshold();
    }

    private boolean amountOverP95(Long source, BigDecimal amount) {
        Instant since = Instant.now().minus(Duration.ofDays(properties.getP95LookbackDays()));
        List<Object[]> rows = transactionRepository.amountStats(source, since);
        if (rows.isEmpty()) {
            return false;
        }
        Object[] row = rows.get(0);
        long sampleCount = row[0] == null ? 0L : ((Number) row[0]).longValue();
        // Not enough history for a trustworthy baseline - don't flag a new account for a large payment.
        if (sampleCount < properties.getP95MinSamples() || row[1] == null) {
            return false;
        }
        BigDecimal p95 = toBigDecimal(row[1]);
        return amount.compareTo(p95) > 0;
    }

    private boolean firstTimePayee(Long source, Long dest) {
        return transactionRepository.countByRoute(source, dest, TxStatus.COMPLETED) == 0L;
    }

    /** Package-private and pure so it can be unit-tested directly with a fixed clock in Phase 6. */
    boolean roundAmountAtMidnight(BigDecimal amount, Instant now) {
        int hour = now.atZone(ZoneOffset.UTC).getHour();
        boolean midnightHours = hour >= properties.getMidnightStartHour() && hour < properties.getMidnightEndHour();
        if (!midnightHours) {
            return false;
        }
        BigDecimal multiple = BigDecimal.valueOf(properties.getRoundAmountMultiple());
        boolean round = amount.signum() > 0 && amount.remainder(multiple).compareTo(BigDecimal.ZERO) == 0;
        return round;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "fraud-scorer-" + n.incrementAndGet());
            t.setDaemon(true); // never block JVM shutdown on a scoring thread
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        scoringPool.shutdownNow();
    }
}
