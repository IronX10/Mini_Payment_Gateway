package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Keyset (cursor) pagination over an account's statement, newest first. The cursor is the
     * last id the client saw; we fetch strictly-smaller ids. Backed by the (account_id, id)
     * index, so this stays O(log n) no matter how deep the client pages - unlike OFFSET, which
     * re-scans and discards every skipped row.
     *
     * @param cursor last seen id, or null for the first page
     */
    @Query("select le from LedgerEntry le where le.accountId = :accountId "
            + "and (:cursor is null or le.id < :cursor) order by le.id desc")
    List<LedgerEntry> findPage(@Param("accountId") Long accountId,
                               @Param("cursor") Long cursor,
                               Pageable pageable);

    /** The invariant check: signed sum across the entire ledger must be exactly zero. */
    @Query(value = "select coalesce(sum(case when direction = 'DEBIT' then -amount else amount end), 0) "
            + "from ledger_entries", nativeQuery = true)
    BigDecimal signedSum();
}
