package com.dixansh.mini_payment_gateway;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end payment behaviour against real Postgres: the happy path through the double-entry
 * ledger, and the Idempotency-Key contract - including the race where many copies of the same
 * request arrive at once and the UNIQUE constraint has to pick exactly one winner.
 */
class PaymentFlowIT extends AbstractIntegrationTest {

    @Test
    void happyPath_movesMoney_writesBalancedLedgerPair_andReconciles() {
        long source = createFundedAccount("1000.00");
        long dest = createAccount().get("id").asLong();

        Response res = pay(UUID.randomUUID().toString(), source, dest, "250.75");

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(balanceOf(source)).isEqualByComparingTo("749.25");
        assertThat(balanceOf(dest)).isEqualByComparingTo("250.75");

        // Exactly one DEBIT and one CREDIT of equal amount for this payment.
        UUID txId = UUID.fromString(res.body().get("id").asString());
        List<String> directions = jdbc.queryForList(
                "SELECT direction::text FROM ledger_entries WHERE transaction_id = ? ORDER BY direction",
                String.class, txId);
        assertThat(directions).containsExactly("CREDIT", "DEBIT");
        BigDecimal signedSum = jdbc.queryForObject("""
                SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)
                FROM ledger_entries WHERE transaction_id = ?""", BigDecimal.class, txId);
        assertThat(signedSum).isEqualByComparingTo("0");

        // The API view and the reconciler agree that the books balance.
        JsonNode account = get("/api/v1/accounts/" + dest).body();
        assertThat(new BigDecimal(account.get("balance").asString()))
                .isEqualByComparingTo(new BigDecimal(account.get("ledgerBalance").asString()));
        assertReconciled();
    }

    @Test
    void insufficientFunds_isRejected_andMovesNothing() {
        long source = createFundedAccount("10.00");
        long dest = createAccount().get("id").asLong();

        Response res = pay(UUID.randomUUID().toString(), source, dest, "10.01");

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.body().get("error").asString()).isEqualTo("insufficient_funds");
        assertThat(balanceOf(source)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(dest)).isEqualByComparingTo("0");
    }

    @Test
    void idempotentReplay_returnsStoredResponse_withoutChargingTwice() {
        long source = createFundedAccount("500.00");
        long dest = createAccount().get("id").asLong();
        String key = UUID.randomUUID().toString();

        Response first = pay(key, source, dest, "100.00");
        Response replay = pay(key, source, dest, "100.00");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(balanceOf(source)).isEqualByComparingTo("400.00");
        assertThat(countTransactions(key)).isEqualTo(1);
    }

    @Test
    void reusedKeyWithDifferentBody_isRejected() {
        long source = createFundedAccount("500.00");
        long dest = createAccount().get("id").asLong();
        String key = UUID.randomUUID().toString();

        assertThat(pay(key, source, dest, "100.00").status()).isEqualTo(201);
        Response reused = pay(key, source, dest, "200.00");

        assertThat(reused.status()).isEqualTo(422);
        assertThat(balanceOf(source)).isEqualByComparingTo("400.00");
    }

    @Test
    void parallelDuplicateKey_exactlyOneExecutes() throws Exception {
        long source = createFundedAccount("1000.00");
        long dest = createAccount().get("id").asLong();
        String key = UUID.randomUUID().toString();
        int threads = 20;

        // Every thread parks on the latch, then all fire together to maximise the collision window.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return pay(key, source, dest, "100.00");
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Response> f : futures) {
            statuses.add(f.get(30, TimeUnit.SECONDS).status());
        }
        pool.shutdown();

        // One winner executes (201); everyone else replays it (200) or saw it in flight (409).
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409);
        assertThat(countTransactions(key)).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(dest)).isEqualByComparingTo("100.00");
    }

    private int countTransactions(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Integer.class, idempotencyKey);
    }

    private void assertReconciled() {
        JsonNode reconcile = get("/internal/reconcile").body();
        assertThat(reconcile.get("balanced").asBoolean()).isTrue();
        assertThat(new BigDecimal(reconcile.get("ledgerSignedSum").asString())).isEqualByComparingTo("0");
        assertThat(reconcile.get("mismatches")).isEmpty();
    }
}
