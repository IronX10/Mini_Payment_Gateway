package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.LockingProperties;
import com.dixansh.mini_payment_gateway.domain.Account;
import com.dixansh.mini_payment_gateway.domain.EntryDirection;
import com.dixansh.mini_payment_gateway.domain.LedgerEntry;
import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.dto.PaymentResponse;
import com.dixansh.mini_payment_gateway.exception.NotFoundException;
import com.dixansh.mini_payment_gateway.exception.UnprocessablePaymentException;
import com.dixansh.mini_payment_gateway.repository.AccountRepository;
import com.dixansh.mini_payment_gateway.repository.LedgerEntryRepository;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import com.dixansh.mini_payment_gateway.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional core: one {@code executeTransfer} = one atomic double-entry transfer.
 *
 * <p>Split out from {@link PaymentService} on purpose. The idempotency orchestration in
 * {@code PaymentService} must react to a unique-constraint violation and then re-read in a
 * <em>fresh</em> transaction; that only works if the execution runs behind its own transactional
 * proxy boundary (a self-invocation would silently share the caller's transaction).
 */
@Service
public class TransferService {

    /** Event type recorded when a transfer settles; the merchant-facing webhook name. */
    public static final String EVENT_PAYMENT_COMPLETED = "payment.completed";

    /** Event recorded when the fraud engine holds a payment for review (Phase 5). */
    public static final String EVENT_PAYMENT_HELD = "payment.held";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LockingProperties lockingProperties;
    private final OutboxService outboxService;

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           LockingProperties lockingProperties,
                           OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.lockingProperties = lockingProperties;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> findByKey(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * Atomic transfer of {@code amount} from source to dest.
     *
     * <p>Steps: insert the PENDING transaction (its UNIQUE idempotency key is the concurrency
     * gate), lock both accounts, validate, move balances, write the DEBIT/CREDIT pair, mark
     * COMPLETED. Any exception rolls the whole unit back.
     *
     * @param eventType the outbox event to record on success (e.g. {@link #EVENT_PAYMENT_COMPLETED}),
     *                  or {@code null} for internal movements (e.g. dev top-ups) that shouldn't emit
     *                  a merchant-facing webhook
     * @throws org.springframework.dao.DataIntegrityViolationException if the idempotency key already exists
     */
    @Transactional
    public Transaction executeTransfer(String idempotencyKey, Long sourceId, Long destId,
                                       BigDecimal amount, String currency, String requestHash,
                                       String eventType) {
        if (sourceId.equals(destId)) {
            throw new UnprocessablePaymentException("same_account", "source and dest must differ");
        }

        // Insert-first: the UNIQUE constraint on idempotency_key is the source of truth. A duplicate
        // key blocks here until the first transaction resolves, then surfaces as a constraint
        // violation - which the caller turns into a replay. Flush so the row (and its generated
        // UUID) exists before the ledger entries reference it via FK.
        Transaction tx = new Transaction();
        tx.setIdempotencyKey(idempotencyKey);
        tx.setSourceAccount(sourceId);
        tx.setDestAccount(destId);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setRequestHash(requestHash);
        tx.setStatus(TxStatus.PENDING);
        tx = transactionRepository.saveAndFlush(tx);

        Loaded loaded = loadAccounts(sourceId, destId);
        applyTransfer(tx, loaded.source(), loaded.dest(), amount, currency, eventType);
        return tx;
    }

    /**
     * Records a payment the fraud engine flagged as {@code HELD} <b>without settling it</b>: the row
     * is inserted, the risk score and reasons are stored for review, and a {@code payment.held} event
     * is recorded - but no balances move and no ledger entries are written. Settlement is deferred to
     * {@link #releaseHeld(UUID)}.
     *
     * <p>Like {@link #executeTransfer}, the insert is the concurrency gate: a duplicate idempotency
     * key surfaces as a {@code DataIntegrityViolationException} for the caller to turn into a replay.
     * Held payments are quarantined <em>before</em> money moves, so fund/currency validation happens
     * at release time, not here.
     */
    @Transactional
    public Transaction holdTransfer(String idempotencyKey, Long sourceId, Long destId, BigDecimal amount,
                                    String currency, String requestHash, int riskScore, String riskReasons) {
        if (sourceId.equals(destId)) {
            throw new UnprocessablePaymentException("same_account", "source and dest must differ");
        }

        Transaction tx = new Transaction();
        tx.setIdempotencyKey(idempotencyKey);
        tx.setSourceAccount(sourceId);
        tx.setDestAccount(destId);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setRequestHash(requestHash);
        tx.setStatus(TxStatus.HELD);
        tx.setRiskScore(riskScore);
        tx.setRiskReasons(riskReasons);
        tx = transactionRepository.saveAndFlush(tx);

        // The stored response replays byte-identically on a retry (status HELD), same contract as a
        // completed payment. The held event and the row commit together (transactional outbox).
        tx.setResponseBody(JsonUtil.toJson(PaymentResponse.from(tx)));
        outboxService.record(tx.getId(), EVENT_PAYMENT_HELD, tx.getResponseBody());
        return tx;
    }

    /**
     * Releases a {@code HELD} payment: performs the settlement that was deferred at hold time - lock
     * the accounts, validate, move balances, write the ledger pair, mark {@code COMPLETED} - and
     * records the {@code payment.completed} event, resuming the normal flow. This is the
     * human-in-the-loop step behind {@code POST /internal/transactions/{id}/release}.
     *
     * <p>Validation runs now, against current balances: if the source can no longer cover the amount
     * the whole unit rolls back (an {@link UnprocessablePaymentException}) and the payment stays
     * {@code HELD}, so an admin can retry after the account is funded.
     */
    @Transactional
    public Transaction releaseHeld(UUID transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("transaction " + transactionId + " not found"));
        if (tx.getStatus() != TxStatus.HELD) {
            throw new UnprocessablePaymentException("not_held",
                    "transaction is not HELD (current status: " + tx.getStatus() + ")");
        }

        Loaded loaded = loadAccounts(tx.getSourceAccount(), tx.getDestAccount());
        applyTransfer(tx, loaded.source(), loaded.dest(), tx.getAmount(), tx.getCurrency(),
                EVENT_PAYMENT_COMPLETED);
        return tx;
    }

    /** Loads both accounts under the configured locking strategy. */
    private Loaded loadAccounts(Long sourceId, Long destId) {
        if (lockingProperties.getStrategy() == LockingProperties.Strategy.PESSIMISTIC) {
            // Lock BOTH rows FOR UPDATE, always in ascending id order. Consistent ordering is what
            // stops two opposite-direction transfers (A->B and B->A) from deadlocking each other.
            List<Long> ids = sourceId < destId ? List.of(sourceId, destId) : List.of(destId, sourceId);
            List<Account> locked = accountRepository.lockAccounts(ids);
            Account source = pick(locked, sourceId, "source");
            Account dest = pick(locked, destId, "dest");
            return new Loaded(source, dest);
        }
        // Optimistic: no DB lock; the @Version column detects a concurrent write at commit and the
        // orchestrator retries. Wins under low contention (no locks held), loses under hot-account
        // storms (retry churn) - which is why pessimistic is the default.
        Account source = accountRepository.findById(sourceId)
                .orElseThrow(() -> new NotFoundException("source account " + sourceId + " not found"));
        Account dest = accountRepository.findById(destId)
                .orElseThrow(() -> new NotFoundException("dest account " + destId + " not found"));
        return new Loaded(source, dest);
    }

    private Account pick(List<Account> locked, Long id, String role) {
        return locked.stream()
                .filter(a -> a.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(role + " account " + id + " not found"));
    }

    private void applyTransfer(Transaction tx, Account source, Account dest, BigDecimal amount,
                               String currency, String eventType) {
        if (!source.getCurrency().equals(currency) || !dest.getCurrency().equals(currency)) {
            throw new UnprocessablePaymentException("currency_mismatch",
                    "payment currency must match both accounts");
        }
        if (source.getBalance().compareTo(amount) < 0) {
            throw new UnprocessablePaymentException("insufficient_funds",
                    "source account has insufficient funds");
        }

        source.setBalance(source.getBalance().subtract(amount));
        dest.setBalance(dest.getBalance().add(amount));

        ledgerEntryRepository.save(new LedgerEntry(tx.getId(), source.getId(), EntryDirection.DEBIT, amount));
        ledgerEntryRepository.save(new LedgerEntry(tx.getId(), dest.getId(), EntryDirection.CREDIT, amount));

        tx.setStatus(TxStatus.COMPLETED);
        tx.setResponseBody(JsonUtil.toJson(PaymentResponse.from(tx)));

        // Same transaction as the balance moves and ledger writes: the payment and the promise to
        // deliver its event commit together, or not at all. This is the transactional-outbox trick.
        // eventType is null for internal movements (dev top-ups) that shouldn't fire a webhook.
        if (eventType != null) {
            outboxService.record(tx.getId(), eventType, tx.getResponseBody());
        }
    }

    private record Loaded(Account source, Account dest) {
    }
}
