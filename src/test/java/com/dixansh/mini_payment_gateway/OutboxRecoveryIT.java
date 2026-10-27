package com.dixansh.mini_payment_gateway;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Crash recovery for the transactional outbox. The dangerous window is "Kafka acked the publish, but
 * the process died before the SENT flag committed": the row is still NEW, so the relay publishes it
 * again. We simulate exactly that by flipping a delivered event back to NEW, then prove the
 * at-least-once contract end to end - the event really is on Kafka twice, yet the merchant is
 * notified once, because the consumer dedupes on (event, endpoint).
 */
class OutboxRecoveryIT extends AbstractIntegrationTest {

    private static final String TOPIC = "payment.events";

    @Test
    void crashBetweenPublishAndCommit_republishes_butMerchantSeesEventOnce() throws Exception {
        try (StubMerchant merchant = new StubMerchant(0)) {
            long source = createFundedAccount("100.00");
            JsonNode dest = createAccount();
            post("/internal/webhooks/endpoints",
                    Map.of("userId", dest.get("userId").asLong(), "url", merchant.url(), "secret", "s3cret"));

            Response payment = pay(UUID.randomUUID().toString(), source, dest.get("id").asLong(), "25.00");
            assertThat(payment.status()).isEqualTo(201);
            UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?",
                    UUID.class, UUID.fromString(payment.body().get("id").asString()));

            // Normal flow first: relayed, consumed, delivered once.
            await().atMost(Duration.ofSeconds(30)).until(() -> merchant.received().size() == 1);
            assertThat(outboxStatus(eventId)).isEqualTo("SENT");

            // "Crash": the SENT flag never committed, so on restart the row is NEW again.
            jdbc.update("UPDATE outbox_events SET status = 'NEW' WHERE id = ?", eventId);

            // The relay recovers it without intervention...
            await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(outboxStatus(eventId)));
            // ...and the topic now genuinely carries the event twice (at-least-once, not exactly-once).
            await().atMost(Duration.ofSeconds(30)).until(() -> countOnTopic(eventId) == 2);

            // The duplicate is absorbed: still one delivery row, still one merchant notification.
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() ->
                    merchant.received().size() == 1 && deliveryRows(eventId) == 1);
        }
    }

    private String outboxStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT status::text FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int deliveryRows(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE event_id = ?", Integer.class, eventId);
    }

    /** Reads the whole topic with a throwaway consumer group and counts records carrying this event id. */
    private int countOnTopic(UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-audit-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        AtomicInteger count = new AtomicInteger();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            int emptyPolls = 0;
            while (emptyPolls < 2) {
                var records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                }
                for (ConsumerRecord<String, String> record : records) {
                    Header header = record.headers().lastHeader("event-id");
                    if (header != null && eventId.toString().equals(new String(header.value(), StandardCharsets.UTF_8))) {
                        count.incrementAndGet();
                    }
                }
            }
        }
        return count.get();
    }
}
