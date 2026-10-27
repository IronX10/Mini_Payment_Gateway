package com.dixansh.mini_payment_gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An account holds a cached balance. The balance is a derived, cached value - the ledger
 * (see {@link LedgerEntry}) is the source of truth. We keep a cached copy for O(1) reads and
 * reconcile it against the ledger via GET /internal/reconcile.
 *
 * <p>{@code version} powers optimistic locking (Phase 2). The DB also enforces
 * {@code balance >= 0} via a CHECK constraint - defence in depth against a logic bug ever
 * driving an account negative.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Money is ALWAYS BigDecimal, never double - see DECISIONS.md on float rounding. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Account(Long userId, String currency) {
        this.userId = userId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO;
    }
}
