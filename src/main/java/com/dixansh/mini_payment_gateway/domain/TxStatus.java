package com.dixansh.mini_payment_gateway.domain;

/**
 * Lifecycle of a payment/transaction. Mirrors the Postgres enum type {@code tx_status}
 * defined in Flyway migration V2__core.sql.
 *
 * PENDING   - row inserted, transfer not yet applied (also the idempotency "in-flight" marker)
 * COMPLETED - money moved, both ledger entries written, balances updated
 * FAILED    - a business rule rejected it (e.g. insufficient funds); no ledger entries exist
 * HELD      - flagged by the fraud hook (Phase 5); awaits manual release
 * REVERSED  - compensated after the fact (reserved for future use)
 */
public enum TxStatus {
    PENDING,
    COMPLETED,
    FAILED,
    HELD,
    REVERSED
}
