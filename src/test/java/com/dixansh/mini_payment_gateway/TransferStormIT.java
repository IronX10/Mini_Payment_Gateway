package com.dixansh.mini_payment_gateway;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency proof: 50 threads hammer a small set of accounts with random transfers in both
 * directions (so lock ordering matters - a naive lock order deadlocks here), with amounts large
 * enough that many attempts overdraw and must be rejected.
 *
 * <p>Deterministic-ish by design: a fixed pool released together by a latch maximises collisions,
 * and the assertions are about <em>invariants</em> (money conserved, ledger sums to zero, nothing
 * negative, cache matches ledger), never about a particular interleaving.
 */
class TransferStormIT extends AbstractIntegrationTest {

    private static final int THREADS = 50;
    private static final int PAYMENTS_PER_THREAD = 10;
    private static final int ACCOUNTS = 5;
    private static final BigDecimal FUNDING = new BigDecimal("1000.00");

    @Test
    void concurrentTransfers_conserveMoney_andKeepLedgerBalanced() throws Exception {
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(createFundedAccount(FUNDING.toPlainString()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            long seed = t;
            futures.add(pool.submit(() -> {
                Random random = new Random(seed);
                start.await();
                for (int i = 0; i < PAYMENTS_PER_THREAD; i++) {
                    int from = random.nextInt(ACCOUNTS);
                    int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
                    String amount = (1 + random.nextInt(400)) + ".00";
                    Response res = pay(UUID.randomUUID().toString(), accounts.get(from), accounts.get(to), amount);
                    statusCounts.computeIfAbsent(res.status(), s -> new AtomicInteger()).incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();

        // Only "settled" or "rejected for funds" (or, under the optimistic strategy, "too much
        // contention") are legitimate outcomes. A 5xx - e.g. an undetected deadlock - fails the test.
        assertThat(statusCounts.keySet()).isSubsetOf(201, 409, 422);
        assertThat(statusCounts.get(201)).as("some transfers must settle").isNotNull();

        BigDecimal total = BigDecimal.ZERO;
        for (Long id : accounts) {
            BigDecimal balance = balanceOf(id);
            assertThat(balance).as("balance of account %d", id).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            total = total.add(balance);
        }
        // A closed system: transfers only move money between these accounts, never create or destroy it.
        assertThat(total).isEqualByComparingTo(FUNDING.multiply(BigDecimal.valueOf(ACCOUNTS)));

        JsonNode reconcile = get("/internal/reconcile").body();
        assertThat(reconcile.get("balanced").asBoolean()).isTrue();
        assertThat(new BigDecimal(reconcile.get("ledgerSignedSum").asString())).isEqualByComparingTo("0");
        assertThat(reconcile.get("mismatches")).isEmpty();
    }
}
