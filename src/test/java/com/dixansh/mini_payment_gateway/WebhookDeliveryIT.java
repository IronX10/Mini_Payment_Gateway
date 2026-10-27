package com.dixansh.mini_payment_gateway;

import com.dixansh.mini_payment_gateway.util.HmacUtil;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The full async pipeline - payment commit -> outbox row -> relay -> Kafka -> consumer -> signed
 * HTTP POST - against a stub merchant that fails on demand. Proves retry-with-backoff on a 500,
 * byte-identical (so stably signed) redeliveries, and the dead-letter path once retries run out.
 * Backoff is shrunk to seconds by the base class so this runs in test time.
 */
class WebhookDeliveryIT extends AbstractIntegrationTest {

    private static final String SECRET = "it-merchant-secret";

    @Test
    void merchant500_isRetried_untilDelivered() throws Exception {
        try (StubMerchant merchant = new StubMerchant(2)) {
            UUID eventId = payToMerchant(merchant);

            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                    assertThat(delivery(eventId).get("status")).isEqualTo("DELIVERED"));

            Map<String, Object> delivery = delivery(eventId);
            assertThat(delivery.get("attempt")).isEqualTo(3);
            assertThat(delivery.get("last_response_code")).isEqualTo(200);

            // Two failures + one success, every one the same event with the same signed body.
            assertThat(merchant.received()).hasSize(3);
            assertThat(merchant.received()).extracting(StubMerchant.Received::webhookId)
                    .containsOnly(eventId.toString());
            assertThat(merchant.received()).extracting(StubMerchant.Received::body)
                    .containsOnly(merchant.received().get(0).body());
            assertThat(merchant.received()).allSatisfy(r ->
                    assertThat(HmacUtil.verify(SECRET, r.body(), r.signature())).isTrue());
        }
    }

    @Test
    void persistentFailure_endsDead_andShowsInFailedQueue() throws Exception {
        try (StubMerchant merchant = new StubMerchant(Integer.MAX_VALUE)) {
            UUID eventId = payToMerchant(merchant);

            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                    assertThat(delivery(eventId).get("status")).isEqualTo("DEAD"));

            // max-attempts is 4 in the test profile: exactly 4 tries, then it stops.
            assertThat(delivery(eventId).get("attempt")).isEqualTo(4);
            assertThat(merchant.received()).hasSize(4);

            JsonNode failed = get("/internal/webhooks/failed").body();
            assertThat(failed.valueStream().map(d -> d.get("eventId").asString()))
                    .contains(eventId.toString());
        }
    }

    /** Pays a fresh merchant account that has a webhook registered at the stub; returns the event id. */
    private UUID payToMerchant(StubMerchant merchant) {
        long source = createFundedAccount("100.00");
        JsonNode dest = createAccount();
        Response endpoint = post("/internal/webhooks/endpoints",
                Map.of("userId", dest.get("userId").asLong(), "url", merchant.url(), "secret", SECRET));
        assertThat(endpoint.status()).isEqualTo(201);

        Response payment = pay(UUID.randomUUID().toString(), source, dest.get("id").asLong(), "10.00");
        assertThat(payment.status()).isEqualTo(201);
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?",
                UUID.class, UUID.fromString(payment.body().get("id").asString()));
    }

    private Map<String, Object> delivery(UUID eventId) {
        return jdbc.queryForMap(
                "SELECT status::text AS status, attempt, last_response_code FROM webhook_deliveries WHERE event_id = ?",
                eventId);
    }
}
