package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unsent rows for this poller. {@code FOR UPDATE SKIP LOCKED} is the key:
     * concurrent pollers (or app instances) each grab a <em>different</em> disjoint batch instead of
     * blocking on each other, so the outbox scales horizontally with zero coordination. Rows stay
     * locked until the surrounding transaction commits, which is when they flip to SENT.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'NEW'
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("limit") int limit);
}
