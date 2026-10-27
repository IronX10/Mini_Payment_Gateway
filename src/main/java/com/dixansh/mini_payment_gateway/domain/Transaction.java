package com.dixansh.mini_payment_gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single logical payment (a transfer between two accounts). This is the aggregate root the
 * client sees; the balanced {@link LedgerEntry} pair is the accounting record underneath it.
 *
 * <p>Foreign keys are stored as plain ids rather than {@code @ManyToOne} associations - it keeps
 * queries explicit and sidesteps lazy-loading / N+1 surprises, which matters on a hot path.
 *
 * <p>Idempotency (Phase 2): {@code idempotencyKey} is UNIQUE. On a retry with the same key we
 * replay {@code responseBody}; {@code requestHash} lets us detect the "same key, different body"
 * abuse case and reject it with 422.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "source_account", nullable = false)
    private Long sourceAccount;

    @Column(name = "dest_account", nullable = false)
    private Long destAccount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Maps to the Postgres native enum {@code tx_status}. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "tx_status", nullable = false)
    private TxStatus status = TxStatus.PENDING;

    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * Total fraud score assigned by the Phase 5 rules engine, or {@code null} if the payment was
     * never scored (internal top-ups, or rows created before Phase 5). Persisted for explainability.
     */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** Comma-separated codes of the rules that fired (e.g. {@code AMOUNT_OVER_P95,FIRST_TIME_PAYEE}). */
    @Column(name = "risk_reasons")
    private String riskReasons;

    /** SHA-256 of the canonical request body; used to detect idempotency-key reuse with a different body. */
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    /** The exact JSON we returned, stored so an idempotent retry replays a byte-identical response. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
