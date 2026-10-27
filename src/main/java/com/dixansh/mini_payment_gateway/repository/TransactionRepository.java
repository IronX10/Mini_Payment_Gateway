package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Keyset pagination for the payments list, newest first, optionally filtered by status.
     * The cursor is a (created_at, id) tuple so ties on the timestamp still page deterministically.
     * A null status matches everything.
     */
    @Query("select t from Transaction t "
            + "where (:status is null or t.status = :status) "
            + "and (:cursorTs is null or t.createdAt < :cursorTs "
            + "     or (t.createdAt = :cursorTs and t.id < :cursorId)) "
            + "order by t.createdAt desc, t.id desc")
    List<Transaction> findPage(@Param("status") TxStatus status,
                               @Param("cursorTs") Instant cursorTs,
                               @Param("cursorId") UUID cursorId,
                               Pageable pageable);

    List<Transaction> findByStatusOrderByCreatedAtDesc(TxStatus status);

    /**
     * Per-account amount baseline for the fraud engine: the number of prior COMPLETED payments from
     * this source in the look-back window, plus their 95th-percentile amount. Returns a single row
     * {@code [sampleCount (long), p95 (BigDecimal or null)]}.
     *
     * <p>Uses {@code percentile_disc} (a real observed amount, no interpolation) rather than
     * {@code percentile_cont} - "the amount at/above which the top 5% of this account's payments
     * sit" is a more defensible fraud baseline than an interpolated value. {@code p95} is null when
     * there is no history.
     */
    @Query(value = """
            SELECT count(*) AS sample_count,
                   percentile_disc(0.95) WITHIN GROUP (ORDER BY amount) AS p95
            FROM transactions
            WHERE source_account = :accountId
              AND status = 'COMPLETED'
              AND created_at >= :since
            """, nativeQuery = true)
    List<Object[]> amountStats(@Param("accountId") Long accountId, @Param("since") Instant since);

    /** How many times this source has already <em>successfully</em> paid this destination. Zero = first-time payee. */
    @Query("select count(t) from Transaction t "
            + "where t.sourceAccount = :source and t.destAccount = :dest and t.status = :status")
    long countByRoute(@Param("source") Long source, @Param("dest") Long dest, @Param("status") TxStatus status);
}
