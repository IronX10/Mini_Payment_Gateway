package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.LockingProperties;
import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.dto.CreatePaymentRequest;
import com.dixansh.mini_payment_gateway.dto.PaymentResult;
import com.dixansh.mini_payment_gateway.exception.ApiException;
import com.dixansh.mini_payment_gateway.exception.IdempotencyConflictException;
import com.dixansh.mini_payment_gateway.exception.IdempotencyKeyReuseException;
import com.dixansh.mini_payment_gateway.exception.UnprocessablePaymentException;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the payment orchestrator - the idempotency + fraud-gating logic that sits ON TOP of
 * the atomic {@link TransferService}. Every collaborator is a Mockito mock, so these tests exercise
 * branch logic (which path is taken, which HTTP status comes back) with zero database or Spring
 * context. The atomic transfer itself is proven separately by the Testcontainers integration tests.
 *
 * <p>{@link LockingProperties} is a real POJO (default PESSIMISTIC), so tests read the same defaults
 * the app ships and flip the strategy only where the optimistic retry loop is the thing under test.
 * Returned {@link Transaction}s are real Lombok POJOs with a pre-set {@code responseBody}, so the
 * replay body is deterministic without dragging in JSON serialization.
 */
class PaymentServiceTest {

    private static final String KEY = "idem-key-1";
    private static final Long SRC = 1L;
    private static final Long DEST = 2L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String CURRENCY = "USD";
    private static final String STORED_JSON = "{\"id\":\"stored\",\"status\":\"COMPLETED\"}";
    private static final String HELD_JSON = "{\"id\":\"held\",\"status\":\"HELD\"}";

    /** The fingerprint PaymentService will compute for the canonical request under test. */
    private static final String MATCHING_HASH = PaymentService.requestHash(SRC, DEST, AMOUNT, CURRENCY);

    private TransferService transferService;
    private TransactionRepository transactionRepository;
    private LockingProperties lockingProperties;
    private VelocityService velocityService;
    private FraudService fraudService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        transactionRepository = mock(TransactionRepository.class);
        lockingProperties = new LockingProperties(); // real defaults: PESSIMISTIC, maxRetries 5
        velocityService = mock(VelocityService.class);
        fraudService = mock(FraudService.class);

        paymentService = new PaymentService(
                transferService, transactionRepository, lockingProperties, velocityService, fraudService);
    }

    private static CreatePaymentRequest request(Long source, Long dest) {
        return new CreatePaymentRequest(source, dest, AMOUNT, CURRENCY);
    }

    private static Transaction tx(TxStatus status, String requestHash, String responseBody) {
        Transaction t = new Transaction();
        t.setStatus(status);
        t.setRequestHash(requestHash);
        t.setResponseBody(responseBody);
        return t;
    }

    @Test
    void sameSourceAndDest_isRejected422_beforeAnyScoringOrTransfer() {
        assertThatThrownBy(() -> paymentService.createPayment(KEY, request(SRC, SRC)))
                .isInstanceOfSatisfying(UnprocessablePaymentException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("same_account");
                });

        // The guard is the very first thing: we neither look up the key, nor score, nor transfer.
        verify(transferService, never()).findByKey(anyString());
        verify(fraudService, never()).score(any(), any(), any());
        verify(transferService, never()).executeTransfer(anyString(), anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void sameKeySameBody_replaysStoredResponseWith200_withoutScoringAgain() {
        // A completed original is found on the fast path; its stored hash matches this request.
        when(transferService.findByKey(KEY))
                .thenReturn(Optional.of(tx(TxStatus.COMPLETED, MATCHING_HASH, STORED_JSON)));

        PaymentResult result = paymentService.createPayment(KEY, request(SRC, DEST));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.json()).isEqualTo(STORED_JSON);
        // Fast-path replay must not re-score, re-transfer, or re-count velocity.
        verify(fraudService, never()).score(any(), any(), any());
        verify(transferService, never()).executeTransfer(anyString(), anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
        verify(velocityService, never()).recordAndCount(anyLong());
    }

    @Test
    void sameKeyDifferentBody_isRejectedAsKeyReuse422() {
        // Same key, but the stored request has a different fingerprint -> client reused a key.
        when(transferService.findByKey(KEY))
                .thenReturn(Optional.of(tx(TxStatus.COMPLETED, "a-totally-different-hash", STORED_JSON)));

        assertThatThrownBy(() -> paymentService.createPayment(KEY, request(SRC, DEST)))
                .isInstanceOfSatisfying(IdempotencyKeyReuseException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("idempotency_key_reuse");
                });

        verify(fraudService, never()).score(any(), any(), any());
    }

    @Test
    void sameKeyWhileOriginalStillPending_returns409Conflict() {
        // The original is still in flight (PENDING) with a matching body -> ask the client to retry.
        when(transferService.findByKey(KEY))
                .thenReturn(Optional.of(tx(TxStatus.PENDING, MATCHING_HASH, null)));

        assertThatThrownBy(() -> paymentService.createPayment(KEY, request(SRC, DEST)))
                .isInstanceOfSatisfying(IdempotencyConflictException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("idempotency_conflict");
                });
    }

    @Test
    void allowedPayment_settlesViaExecuteTransfer_andReturns201() {
        when(transferService.findByKey(KEY)).thenReturn(Optional.empty());
        when(fraudService.score(SRC, DEST, AMOUNT)).thenReturn(FraudDecision.allow());
        when(transferService.executeTransfer(eq(KEY), eq(SRC), eq(DEST), eq(AMOUNT), eq(CURRENCY),
                anyString(), eq(TransferService.EVENT_PAYMENT_COMPLETED)))
                .thenReturn(tx(TxStatus.COMPLETED, MATCHING_HASH, STORED_JSON));

        PaymentResult result = paymentService.createPayment(KEY, request(SRC, DEST));

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.json()).isEqualTo(STORED_JSON);
        // Settled, not held; and the source's velocity window is incremented exactly once.
        verify(transferService, never()).holdTransfer(anyString(), anyLong(), anyLong(), any(),
                anyString(), anyString(), anyInt(), any());
        verify(velocityService, times(1)).recordAndCount(SRC);
    }

    @Test
    void heldPayment_quarantinesViaHoldTransfer_andReturns202() {
        int score = 55;
        List<String> reasons = List.of(FraudService.RULE_AMOUNT_OVER_P95, FraudService.RULE_FIRST_TIME_PAYEE);
        String expectedCsv = FraudService.RULE_AMOUNT_OVER_P95 + "," + FraudService.RULE_FIRST_TIME_PAYEE;

        when(transferService.findByKey(KEY)).thenReturn(Optional.empty());
        when(fraudService.score(SRC, DEST, AMOUNT)).thenReturn(new FraudDecision(true, score, reasons));
        when(transferService.holdTransfer(eq(KEY), eq(SRC), eq(DEST), eq(AMOUNT), eq(CURRENCY),
                anyString(), eq(score), eq(expectedCsv)))
                .thenReturn(tx(TxStatus.HELD, MATCHING_HASH, HELD_JSON));

        PaymentResult result = paymentService.createPayment(KEY, request(SRC, DEST));

        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.json()).isEqualTo(HELD_JSON);
        // Held payments don't settle: executeTransfer is never called, but a held attempt still
        // counts against velocity so a burst of held attempts escalates too.
        verify(transferService, never()).executeTransfer(anyString(), anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
        verify(transferService, times(1)).holdTransfer(eq(KEY), eq(SRC), eq(DEST), eq(AMOUNT), eq(CURRENCY),
                anyString(), eq(score), eq(expectedCsv));
        verify(velocityService, times(1)).recordAndCount(SRC);
    }

    @Test
    void concurrentInsertRace_replaysTheWinnerInsteadOfFailing() {
        // Fast path sees nothing; between our check and the insert, a concurrent request wins the
        // UNIQUE key. Our insert trips the constraint, and we replay the winner's stored response.
        Transaction winner = tx(TxStatus.COMPLETED, MATCHING_HASH, STORED_JSON);
        when(transferService.findByKey(KEY))
                .thenReturn(Optional.empty())   // fast-path check
                .thenReturn(Optional.of(winner)); // re-read inside the catch
        when(fraudService.score(SRC, DEST, AMOUNT)).thenReturn(FraudDecision.allow());
        when(transferService.executeTransfer(eq(KEY), eq(SRC), eq(DEST), eq(AMOUNT), eq(CURRENCY),
                anyString(), eq(TransferService.EVENT_PAYMENT_COMPLETED)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        PaymentResult result = paymentService.createPayment(KEY, request(SRC, DEST));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.json()).isEqualTo(STORED_JSON);
        // We lost the race, so we never counted a settlement against velocity.
        verify(velocityService, never()).recordAndCount(anyLong());
    }

    @Test
    void optimisticContention_retriesUpToTheLimitThenReturns409() {
        lockingProperties.setStrategy(LockingProperties.Strategy.OPTIMISTIC);
        lockingProperties.setMaxRetries(3);
        lockingProperties.setBaseBackoffMillis(0); // keep the unit test instant (no real sleeping)

        when(transferService.findByKey(KEY)).thenReturn(Optional.empty());
        when(fraudService.score(SRC, DEST, AMOUNT)).thenReturn(FraudDecision.allow());
        // Every attempt loses the @Version check.
        when(transferService.executeTransfer(eq(KEY), eq(SRC), eq(DEST), eq(AMOUNT), eq(CURRENCY),
                anyString(), eq(TransferService.EVENT_PAYMENT_COMPLETED)))
                .thenThrow(new OptimisticLockingFailureException("row was updated by another transaction"));

        assertThatThrownBy(() -> paymentService.createPayment(KEY, request(SRC, DEST)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("concurrent_update");
                });

        // Exactly maxRetries settlement attempts were made before giving up; nothing was counted.
        verify(transferService, times(3)).executeTransfer(anyString(), anyLong(), anyLong(), any(),
                anyString(), anyString(), anyString());
        verify(velocityService, never()).recordAndCount(anyLong());
    }
}
