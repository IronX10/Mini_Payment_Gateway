package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.LockingProperties;
import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.dto.CreatePaymentRequest;
import com.dixansh.mini_payment_gateway.dto.CursorPage;
import com.dixansh.mini_payment_gateway.dto.PaymentResponse;
import com.dixansh.mini_payment_gateway.dto.PaymentResult;
import com.dixansh.mini_payment_gateway.exception.ApiException;
import com.dixansh.mini_payment_gateway.exception.IdempotencyConflictException;
import com.dixansh.mini_payment_gateway.exception.IdempotencyKeyReuseException;
import com.dixansh.mini_payment_gateway.exception.NotFoundException;
import com.dixansh.mini_payment_gateway.exception.UnprocessablePaymentException;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import com.dixansh.mini_payment_gateway.util.CursorCodec;
import com.dixansh.mini_payment_gateway.util.HashUtil;
import com.dixansh.mini_payment_gateway.util.JsonUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates payment creation: idempotency handling on top, the atomic transfer
 * ({@link TransferService}) underneath. Deliberately NOT {@code @Transactional} - it needs to
 * react to a constraint violation from one transaction and then read in a fresh one, so it must
 * sit outside any single transaction boundary.
 */
@Service
public class PaymentService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TransferService transferService;
    private final TransactionRepository transactionRepository;
    private final LockingProperties lockingProperties;
    private final VelocityService velocityService;
    private final FraudService fraudService;

    public PaymentService(TransferService transferService,
                          TransactionRepository transactionRepository,
                          LockingProperties lockingProperties,
                          VelocityService velocityService,
                          FraudService fraudService) {
        this.transferService = transferService;
        this.transactionRepository = transactionRepository;
        this.lockingProperties = lockingProperties;
        this.velocityService = velocityService;
        this.fraudService = fraudService;
    }

    /**
     * Create a payment, honouring the Idempotency-Key contract:
     * <ul>
     *   <li>same key + same body -&gt; replay the stored response (200);</li>
     *   <li>same key + different body -&gt; 422 (key reuse);</li>
     *   <li>same key while the original is still in flight -&gt; 409 (retry later).</li>
     * </ul>
     */
    public PaymentResult createPayment(String idempotencyKey, CreatePaymentRequest req) {
        String key = normalizeKey(idempotencyKey);
        String hash = requestHash(req.source(), req.dest(), req.amount(), req.currency());

        // Reject a statically-invalid payment up front, so we neither score nor hold something that
        // could never settle. (executeTransfer re-checks this under the lock as defense in depth.)
        if (req.source().equals(req.dest())) {
            throw new UnprocessablePaymentException("same_account", "source and dest must differ");
        }

        // Fast path: a completed retry usually arrives after the original finished, so we can
        // replay without touching the write path at all.
        Optional<Transaction> existing = transferService.findByKey(key);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), hash);
        }

        // Score once, before the (possibly retried) settlement. Scoring is bounded and fail-open, so
        // a slow or unavailable risk path never delays or fails a payment - it defaults to allow.
        FraudDecision decision = fraudService.score(req.source(), req.dest(), req.amount());

        // A held payment doesn't move money, so it has no account contention and never needs the
        // optimistic-retry loop; only the settling path does.
        int maxAttempts = (!decision.hold()
                && lockingProperties.getStrategy() == LockingProperties.Strategy.OPTIMISTIC)
                ? Math.max(1, lockingProperties.getMaxRetries())
                : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Transaction tx = decision.hold()
                        ? transferService.holdTransfer(key, req.source(), req.dest(), req.amount(),
                                req.currency(), hash, decision.score(), decision.reasonsCsv())
                        : transferService.executeTransfer(key, req.source(), req.dest(), req.amount(),
                                req.currency(), hash, TransferService.EVENT_PAYMENT_COMPLETED);
                // Count every fresh attempt (allowed or held) against the source's velocity window so
                // a burst escalates. Replays never reach here, so a retry isn't double-counted.
                velocityService.recordAndCount(req.source());
                return decision.hold() ? PaymentResult.held(bodyOf(tx)) : PaymentResult.created(bodyOf(tx));
            } catch (DataIntegrityViolationException raced) {
                // Another request inserted the same key between our fast-path check and this insert.
                // Whoever won owns the outcome; we replay it.
                Transaction now = transferService.findByKey(key).orElseThrow(() -> raced);
                return replayOrConflict(now, hash);
            } catch (OptimisticLockingFailureException contention) {
                if (attempt >= maxAttempts) {
                    throw new ApiException(HttpStatus.CONFLICT, "concurrent_update",
                            "too much contention on the account; please retry");
                }
                backoffWithJitter(attempt);
            }
        }
        // Unreachable: the loop either returns or throws.
        throw new IllegalStateException("payment retry loop exited unexpectedly");
    }

    private PaymentResult replayOrConflict(Transaction tx, String requestHash) {
        if (!tx.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(
                    "Idempotency-Key was already used with a different request body");
        }
        if (tx.getStatus() == TxStatus.PENDING) {
            throw new IdempotencyConflictException("the original request is still being processed");
        }
        return PaymentResult.replayed(bodyOf(tx));
    }

    private String bodyOf(Transaction tx) {
        return tx.getResponseBody() != null ? tx.getResponseBody() : JsonUtil.toJson(PaymentResponse.from(tx));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID id) {
        return transactionRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new NotFoundException("payment " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public CursorPage<PaymentResponse> listPayments(TxStatus status, String cursor, Integer limit) {
        int size = normalizeLimit(limit);
        Object[] decoded = CursorCodec.decodePayment(cursor);
        Instant cursorTs = decoded == null ? null : (Instant) decoded[0];
        UUID cursorId = decoded == null ? null : (UUID) decoded[1];

        List<Transaction> rows = transactionRepository.findPage(
                status, cursorTs, cursorId, PageRequest.of(0, size + 1));

        String next = null;
        if (rows.size() > size) {
            Transaction last = rows.get(size - 1);
            next = CursorCodec.encodePayment(last.getCreatedAt(), last.getId());
            rows = rows.subList(0, size);
        }
        List<PaymentResponse> items = new ArrayList<>(rows.size());
        for (Transaction t : rows) {
            items.add(PaymentResponse.from(t));
        }
        return new CursorPage<>(items, next);
    }

    /** Canonical request fingerprint used to detect idempotency-key reuse with a different body. */
    public static String requestHash(Long source, Long dest, BigDecimal amount, String currency) {
        String canonical = source + "|" + dest + "|"
                + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "|" + currency;
        return HashUtil.sha256Hex(canonical);
    }

    private String normalizeKey(String idempotencyKey) {
        return (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim();
    }

    private void backoffWithJitter(int attempt) {
        long base = lockingProperties.getBaseBackoffMillis() * attempt;
        long sleep = base + ThreadLocalRandom.current().nextLong(base + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", "request interrupted");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
