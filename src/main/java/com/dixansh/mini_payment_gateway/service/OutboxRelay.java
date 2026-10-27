package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.OutboxProperties;
import com.dixansh.mini_payment_gateway.domain.OutboxEvent;
import com.dixansh.mini_payment_gateway.domain.OutboxStatus;
import com.dixansh.mini_payment_gateway.repository.OutboxEventRepository;
import com.dixansh.mini_payment_gateway.util.JsonUtil;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ships {@code NEW} outbox rows to Kafka and marks them {@code SENT}. The claim-publish-mark cycle
 * runs in one transaction:
 *
 * <ol>
 *   <li>{@code claimBatch} locks a disjoint set of NEW rows with {@code FOR UPDATE SKIP LOCKED};</li>
 *   <li>each row is published to Kafka and its ack awaited;</li>
 *   <li>rows flip to SENT and the transaction commits, releasing the locks.</li>
 * </ol>
 *
 * <p>If the process dies after a Kafka ack but before commit, the row stays NEW and is re-published
 * next tick - a duplicate. That is the deliberate <em>at-least-once</em> guarantee; consumers
 * dedupe on event id. Exactly-once end-to-end is essentially a myth once a network is involved.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       OutboxProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /**
     * Publishes one batch. Returns how many rows were shipped so the poller can loop while the
     * backlog is deep and idle when it isn't.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.claimBatch(properties.getBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxEvent event : batch) {
            publish(event);
            event.setStatus(OutboxStatus.SENT);
        }
        log.debug("outbox relay published {} event(s)", batch.size());
        return batch.size();
    }

    private void publish(OutboxEvent event) {
        String aggregateId = event.getAggregateId().toString();
        String value = JsonUtil.envelope(
                event.getId().toString(),
                event.getEventType(),
                aggregateId,
                event.getCreatedAt().toString(),
                event.getPayload());

        // Event metadata rides in headers so the consumer never has to parse the body: it dedupes on
        // event-id and resolves the merchant from aggregate-id, then forwards `value` verbatim.
        List<Header> headers = new ArrayList<>(3);
        headers.add(header("event-id", event.getId().toString()));
        headers.add(header("event-type", event.getEventType()));
        headers.add(header("aggregate-id", aggregateId));

        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.getTopic(), null, aggregateId, value, headers);
        try {
            // Block for the broker ack: only a confirmed publish may be marked SENT.
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing outbox event " + event.getId(), e);
        } catch (Exception e) {
            // Rethrow to roll the batch back; NEW rows are retried on the next tick.
            throw new IllegalStateException("failed to publish outbox event " + event.getId(), e);
        }
    }

    private static Header header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
