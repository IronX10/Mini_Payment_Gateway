# LOAD_TEST.md

Load-test log for the gateway. These are **local laptop numbers**, recorded honestly with the environment they came from. They show relative behaviour between configurations on one machine, not production capacity.

## Environment (run of 2026-10-27)

| | |
|---|---|
| Machine | Apple M1 (8 cores), 8 GB RAM, macOS 27.0.1 |
| Docker | Docker Desktop 29.6.1, VM limited to 8 CPUs / ~3.8 GB |
| Stack | `docker compose up -d --build`: app + Postgres 16 + Redis 7 + Kafka 3.8 (KRaft), all on the same laptop as the load generator |
| App | Docker image from this repo (Java 17 JRE, `MaxRAMPercentage=75`) |
| Tool | k6 v2.3.0 |

Everything (load generator, app, database, broker) shares the same 8 cores, so absolute numbers are pessimistic and noisy. Read the **comparisons**, not the absolutes.

## Procedure

1. `docker compose up -d --build` and wait for `GET /actuator/health` to report `UP`.
2. For each stage, recreate the app with that stage's toggles (compose passes them through), e.g.
   ```bash
   MPG_LOCKING_STRATEGY=OPTIMISTIC MPG_RATELIMIT_ENABLED=false MPG_FRAUD_ENABLED=false docker compose up -d app
   ```
3. Create a fresh source and destination account, and fund the source with 10,000,000.
4. Warm up the JIT with a discarded 15 s run, then measure:
   ```bash
   SRC=<id> DEST=<id> k6 run --vus 50 --duration 60s --summary-trend-stats 'avg,med,p(95),p(99),max' load/payments_load.js
   ```
5. After each stage, `GET /internal/reconcile` must report `balanced: true` with no mismatches.

**Workload:** 50 closed-loop virtual users, each sending `POST /api/v1/payments` for 1.00 INR from the **same source to the same destination**, with a fresh Idempotency-Key per request. This is the worst case for contention: every request fights over the same two account rows. Fraud scoring is disabled in all stages; otherwise the velocity rule would hold almost every payment and we'd be measuring the hold path.

## Results — payments

| Stage | Config | Settled TPS (201/s) | P50 | P95 | P99 | Errors | Books balanced |
|---|---|---|---|---|---|---|---|
| (a) pessimistic | `PESSIMISTIC`, limiter off | **255** | 158 ms | 452 ms | 651 ms | 0% | ✅ |
| (a) repeat run | same | 218 | 187 ms | 516 ms | 739 ms | 0% | ✅ |
| (b) optimistic | `OPTIMISTIC`, limiter off | **347** (+ 70/s 409s) | 44 ms | 356 ms | 416 ms | **16.7%** `409 concurrent_update` | ✅ |
| (c) limiter in path | `PESSIMISTIC`, limiter on with limits raised so it never throttles | 229 | 176 ms | 504 ms | 719 ms | 0% | ✅ |

### What the numbers say

- **Run-to-run noise is about ±15%** (255 vs 218 TPS for identical runs), because the load generator and the whole stack share one laptop. Differences smaller than that aren't meaningful.
- **Pessimistic vs optimistic on a hot row is a real trade-off, not a free win.** Optimistic locking pushed about 36% more payments through with a much lower median latency, but **1 in 6 requests failed** with `409` after exhausting its 5 retries. Pessimistic locking queued everyone on the row lock and failed nobody. For payments, where a failed request means a confused customer and a client retry, pessimistic is the right default under this kind of contention; optimistic suits a spread of mostly-independent accounts. This is the measured version of the locking trade-off in [DECISIONS.md](DECISIONS.md).
- **The distributed rate limiter's overhead is within noise.** With a Redis token-bucket check on every request (the limit raised so it never fires), throughput was 229 TPS, between the two pessimistic runs without it. On this setup the limiter costs less than the variance can resolve.
- **Correctness held under every stage:** after 13k–25k payments per run, the ledger summed to zero and every cached balance matched its ledger.

## Results — rate limiter smoke test (Phase 4 checkpoint)

`ACCOUNT=<id> k6 run load/ratelimit_smoke.js`, with default settings (limits 20 req/s burst, 100 req/min sustained per API key). It runs 10 VUs hammering `GET /api/v1/accounts/{id}` with one API key for 10 s, while a second key sends a polite 5 req/s.

| Check | Result |
|---|---|
| Requests allowed for the hot key in 10 s | **116**, which is exactly the bucket's 100 capacity + 10 s × 100/60 refill |
| Requests rejected with `429` | ~60,000–71,000 (rejections served at ~6,000–7,000 req/s, P95 ≈ 3 ms) |
| Every `429` carries `Retry-After` | ✅ |
| Second key throttled by the first key's traffic | **0 times**: limits are per key, not global |
| Cached `GET /accounts/{id}` latency (allowed requests) | median 6–7 ms; P95 140–180 ms is inflated by the burst of first requests while all 10 VUs start at once |

Rejected requests are far cheaper than served ones (single-digit ms at thousands per second), which is the point of putting the limiter first in the filter chain.

## Not measured yet

- **Max sustainable TPS before P95 degrades.** These are fixed 50-VU runs. A ramping-arrival-rate run would find the knee.
- **Spread-out traffic.** Payments across many independent accounts, where optimistic locking should shine.
- **Dedicated hardware.** Running the load generator on a separate machine would remove the shared-CPU noise.
