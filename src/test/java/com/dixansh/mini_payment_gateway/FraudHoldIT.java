package com.dixansh.mini_payment_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 5 checkpoint end to end: a burst of payments from one account trips the velocity rule
 * (the Redis counter), the next payment is HELD (202) with its reasons recorded and no money moved,
 * it shows up in the review queue, and releasing it settles it through the normal ledger path.
 *
 * <p>Fraud is re-enabled for this class only (the shared integration context switches it off), so
 * Spring builds a second context against the same containers. The scoring budget is raised from
 * 50 ms so a cold JVM or a slow CI runner can't make scoring time out and fail open mid-test.
 */
@TestPropertySource(properties = {
        "mpg.fraud.enabled=true",
        "mpg.fraud.budget-millis=2000"
})
class FraudHoldIT extends AbstractIntegrationTest {

    /** Generous cap: the threshold is 10/min, but a burst straddling a minute boundary restarts the count. */
    private static final int MAX_BURST = 25;

    @Test
    void velocityBurst_holdsPayment_withoutMovingMoney_thenReleaseSettlesIt() {
        long source = createFundedAccount("1000.00");
        long dest = createAccount().get("id").asLong();

        Response held = null;
        int settled = 0;
        for (int i = 0; i < MAX_BURST && held == null; i++) {
            // Non-round amount so the round-amount-at-midnight rule can never contribute.
            Response res = pay(UUID.randomUUID().toString(), source, dest, "10.50");
            if (res.status() == 202) {
                held = res;
            } else {
                assertThat(res.status()).isEqualTo(201);
                settled++;
            }
        }

        assertThat(held).as("a burst of %d payments must trip the velocity rule", MAX_BURST).isNotNull();
        assertThat(settled).as("payments below the velocity threshold settle normally").isGreaterThanOrEqualTo(10);
        assertThat(held.body().get("status").asString()).isEqualTo("HELD");
        String heldId = held.body().get("id").asString();

        // Held means not settled: no ledger entries, and balances reflect only the settled payments.
        BigDecimal settledTotal = new BigDecimal("10.50").multiply(BigDecimal.valueOf(settled));
        assertThat(balanceOf(source)).isEqualByComparingTo(new BigDecimal("1000.00").subtract(settledTotal));
        assertThat(balanceOf(dest)).isEqualByComparingTo(settledTotal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class, UUID.fromString(heldId))).isZero();

        // It's in the review queue, with the reason it was held.
        JsonNode queued = get("/internal/held").body().valueStream()
                .filter(p -> p.get("id").asString().equals(heldId))
                .findFirst().orElseThrow();
        assertThat(queued.get("riskReasons").valueStream().map(JsonNode::asString)).contains("VELOCITY");
        assertThat(queued.get("riskScore").asInt()).isGreaterThanOrEqualTo(50);

        // Release: HELD -> COMPLETED, money moves, and the books still balance.
        Response released = post("/internal/transactions/" + heldId + "/release", null);
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("status").asString()).isEqualTo("COMPLETED");
        BigDecimal afterRelease = settledTotal.add(new BigDecimal("10.50"));
        assertThat(balanceOf(dest)).isEqualByComparingTo(afterRelease);
        assertThat(balanceOf(source)).isEqualByComparingTo(new BigDecimal("1000.00").subtract(afterRelease));

        JsonNode reconcile = get("/internal/reconcile").body();
        assertThat(reconcile.get("balanced").asBoolean()).isTrue();
        assertThat(reconcile.get("mismatches")).isEmpty();

        // A second release is rejected: it is no longer HELD.
        assertThat(post("/internal/transactions/" + heldId + "/release", null).status()).isEqualTo(422);
    }
}
