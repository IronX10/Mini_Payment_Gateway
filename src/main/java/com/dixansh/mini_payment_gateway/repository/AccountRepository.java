package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findFirstByUserIdAndCurrency(Long userId, String currency);

    /**
     * Pessimistic locking path (Phase 2). Locks the given account rows FOR UPDATE.
     * Callers MUST pass ids sorted ascending so every transfer grabs locks in the same order -
     * that is what prevents two opposite-direction transfers from deadlocking each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id asc")
    List<Account> lockAccounts(@Param("ids") List<Long> ids);

    /** Ledger-derived balance for one account: SUM(credits) - SUM(debits). Source of truth. */
    @Query(value = "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0) "
            + "from ledger_entries where account_id = :accountId", nativeQuery = true)
    BigDecimal derivedBalance(@Param("accountId") Long accountId);
}
