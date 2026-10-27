package com.dixansh.mini_payment_gateway.domain;

/**
 * Direction of a single ledger entry. Mirrors the Postgres enum {@code entry_dir}.
 *
 * Double-entry rule: every transaction writes exactly one DEBIT (money leaving an account)
 * and one CREDIT (money arriving), of equal amount. The signed sum across all entries is
 * therefore always zero - the invariant the whole system is built to preserve.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
