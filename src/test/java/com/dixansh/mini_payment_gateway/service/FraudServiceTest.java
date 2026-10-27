package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.FraudProperties;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the fraud rules engine. Collaborators are Mockito mocks; {@link FraudProperties} is
 * a real POJO so the default weights/thresholds under test are exactly the ones that ship.
 *
 * <p>Mocks are created manually (not via {@code MockitoExtension}) so a shared "all rules neutral"
 * setup can stub collaborators that an individual test doesn't exercise, without tripping strict-stub
 * checks. Each test then flips just the one rule it's about - the classic way to prove rules are
 * independent.
 *
 * <p>Two rules can't be neutralised through a mock because they don't call a collaborator:
 * {@code roundAmountAtMidnight} reads the wall clock, so the isolation tests use non-round amounts
 * (e.g. 1234.56) that can never trip it whatever the hour; it is then tested directly with fixed
 * {@link Instant}s.
 */
class FraudServiceTest {

    private static final Long SRC = 1L;
    private static final Long DEST = 2L;
    /** Amount that is NOT a whole multiple of 1000, so the round-amount-at-midnight rule never fires. */
    private static final BigDecimal NON_ROUND = new BigDecimal("1234.56");

    private FraudProperties properties;
    private TransactionRepository transactionRepository;
    private VelocityService velocityService;
    private FraudService fraudService;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
        // Generous budget so rule logic is never flaky on a slow/loaded CI worker; the strict budget
        // is exercised deliberately in the timeout test below.
        properties.setBudgetMillis(5000);

        transactionRepository = mock(TransactionRepository.class);
        velocityService = mock(VelocityService.class);

        // Baseline: every rule neutral. Individual tests override the one they're about.
        when(velocityService.currentCount(anyLong())).thenReturn(0L);                    // below threshold
        when(transactionRepository.amountStats(anyLong(), any(Instant.class))).thenReturn(List.of()); // no baseline
        when(transactionRepository.countByRoute(anyLong(), anyLong(), eq(TxStatus.COMPLETED))).thenReturn(1L); // not first-time

        fraudService = new FraudService(properties, transactionRepository, velocityService);
    }

    @AfterEach
    void tearDown() {
        fraudService.shutdown();
    }

    @Test
    void disabled_allowsWithoutScoring() {
        properties.setEnabled(false);

        FraudDecision decision = fraudService.score(SRC, DEST, new BigDecimal("999999.00"));

        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isZero();
        assertThat(decision.reasons()).isEmpty();
        // Scoring was skipped entirely - no rule touched a collaborator.
        verify(velocityService, never()).currentCount(anyLong());
        verify(transactionRepository, never()).amountStats(anyLong(), any());
    }

    @Test
    void noRulesFire_allows() {
        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);

        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isZero();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void velocityRule_aloneCrossesThreshold_holds() {
        // Default: velocity weight 50 == threshold 50, so a clear burst holds on its own.
        when(velocityService.currentCount(SRC)).thenReturn(10L); // >= velocity-threshold (10)

        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.score()).isEqualTo(50);
        assertThat(decision.reasons()).containsExactly(FraudService.RULE_VELOCITY);
    }

    @Test
    void velocityRule_justBelowThreshold_doesNotFire() {
        when(velocityService.currentCount(SRC)).thenReturn(9L); // one under the threshold

        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);

        assertThat(decision.hold()).isFalse();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void amountOverP95_firesOnlyWithEnoughHistory() {
        // 20 prior samples (== min-samples) with P95 = 100.00; a 2000.50 payment is well over it.
        when(transactionRepository.amountStats(eq(SRC), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{20L, new BigDecimal("100.00")}));

        FraudDecision decision = fraudService.score(SRC, DEST, new BigDecimal("2000.50"));

        // 40 < 50, so amount-over-P95 alone doesn't hold - but the score and reason are recorded.
        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isEqualTo(40);
        assertThat(decision.reasons()).containsExactly(FraudService.RULE_AMOUNT_OVER_P95);
    }

    @Test
    void amountOverP95_suppressedBelowMinSamples() {
        // Only 19 prior samples (< min-samples 20): a new account isn't flagged for a large payment.
        when(transactionRepository.amountStats(eq(SRC), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{19L, new BigDecimal("100.00")}));

        FraudDecision decision = fraudService.score(SRC, DEST, new BigDecimal("2000.50"));

        assertThat(decision.hold()).isFalse();
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void amountOverP95_suppressedWhenNoP95() {
        // Enough rows counted but a null percentile (no comparable history) - must not NPE or fire.
        when(transactionRepository.amountStats(eq(SRC), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{25L, null}));

        FraudDecision decision = fraudService.score(SRC, DEST, new BigDecimal("2000.50"));

        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void firstTimePayee_fires_butNotEnoughToHoldAlone() {
        when(transactionRepository.countByRoute(SRC, DEST, TxStatus.COMPLETED)).thenReturn(0L);

        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);

        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isEqualTo(15);
        assertThat(decision.reasons()).containsExactly(FraudService.RULE_FIRST_TIME_PAYEE);
    }

    @Test
    void corroboratingSoftSignals_addUpToAHold() {
        // A large payment (over P95) TO A BRAND-NEW PAYEE: 40 + 15 = 55 >= 50 -> held.
        when(transactionRepository.amountStats(eq(SRC), any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{20L, new BigDecimal("100.00")}));
        when(transactionRepository.countByRoute(SRC, DEST, TxStatus.COMPLETED)).thenReturn(0L);

        FraudDecision decision = fraudService.score(SRC, DEST, new BigDecimal("2000.50"));

        assertThat(decision.hold()).isTrue();
        assertThat(decision.score()).isEqualTo(55);
        assertThat(decision.reasons())
                .containsExactlyInAnyOrder(FraudService.RULE_AMOUNT_OVER_P95, FraudService.RULE_FIRST_TIME_PAYEE);
    }

    @Test
    void scoring_defaultAllowsWhenItExceedsTheBudget() {
        // A rule that would definitely hold, but the read blocks far longer than the budget.
        properties.setBudgetMillis(50);
        when(velocityService.currentCount(SRC)).thenAnswer(inv -> {
            Thread.sleep(500); // exceeds the 50ms wall-clock budget
            return 100L;
        });

        long start = System.nanoTime();
        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Fail-open: the payment is allowed, and the caller waited ~budget, not ~500ms.
        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isZero();
        assertThat(elapsedMs).isLessThan(400);
    }

    @Test
    void scoring_defaultAllowsWhenARuleThrows() {
        // Any error inside scoring must fail open, never propagate to the payment path.
        when(transactionRepository.amountStats(anyLong(), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        FraudDecision decision = fraudService.score(SRC, DEST, NON_ROUND);

        assertThat(decision.hold()).isFalse();
        assertThat(decision.score()).isZero();
        // The caller (the payment's request thread) was never interrupted, so failing open must not
        // leave its interrupt flag set - that would break the DB work the payment does next.
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    // --- roundAmountAtMidnight: pure, tested directly with fixed instants (no clock dependency) ---

    @Test
    void roundAtMidnight_trueForRoundAmountInWindow() {
        // 02:00 UTC is inside [0,5); 5000 is a whole multiple of 1000.
        assertThat(fraudService.roundAmountAtMidnight(new BigDecimal("5000"), Instant.parse("2026-01-01T02:00:00Z")))
                .isTrue();
    }

    @Test
    void roundAtMidnight_falseOutsideTheMidnightWindow() {
        // Same round amount at midday - the time half of the rule fails.
        assertThat(fraudService.roundAmountAtMidnight(new BigDecimal("5000"), Instant.parse("2026-01-01T12:00:00Z")))
                .isFalse();
    }

    @Test
    void roundAtMidnight_falseAtTheExclusiveUpperBound() {
        // 05:00 is the exclusive end of [0,5) - must not fire.
        assertThat(fraudService.roundAmountAtMidnight(new BigDecimal("5000"), Instant.parse("2026-01-01T05:00:00Z")))
                .isFalse();
    }

    @Test
    void roundAtMidnight_falseForNonRoundAmountInWindow() {
        assertThat(fraudService.roundAmountAtMidnight(new BigDecimal("5432.10"), Instant.parse("2026-01-01T02:00:00Z")))
                .isFalse();
    }

    @Test
    void roundAtMidnight_falseForZeroOrNegative() {
        Instant midnight = Instant.parse("2026-01-01T02:00:00Z");
        assertThat(fraudService.roundAmountAtMidnight(BigDecimal.ZERO, midnight)).isFalse();
        assertThat(fraudService.roundAmountAtMidnight(new BigDecimal("-5000"), midnight)).isFalse();
    }
}
