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
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional-outbox row. Written in the SAME database transaction as the payment it describes,
 * which is the whole point: "payment committed" and "event will be delivered" become one atomic
 * fact. A publish-after-commit approach can lose the event if the process dies in the gap;
 * publish-inside-commit can emit an event for a transaction that then rolls back. The outbox
 * sidesteps both.
 *
 * <p>A separate poller ({@code OutboxRelay}) ships {@code NEW} rows to Kafka and flips them to
 * {@code SENT}. In production you might replace the poller with CDC (Debezium) tailing the WAL;
 * the table contract stays the same.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The transaction (payment) this event is about; used as the Kafka partition key. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** The event body delivered to merchants, stored verbatim as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "outbox_status", nullable = false)
    private OutboxStatus status = OutboxStatus.NEW;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OutboxEvent(UUID aggregateId, String eventType, String payload) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.NEW;
    }
}
