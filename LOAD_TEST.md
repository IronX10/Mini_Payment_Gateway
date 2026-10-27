# LOAD_TEST.md

Load-test log for the payment endpoint. Numbers are recorded honestly, with the environment they came from; local laptop numbers are fine as long as they are labelled.

> **Status:** not yet run. The script and procedure are ready; fill in the tables below after each run.

## Environment

| | |
|---|---|
| Machine | _e.g. MacBook, CPU, RAM_ |
| Infra | `docker compose up` (Postgres 16, Kafka 7.6.1, Redis 7) on the same machine |
| App | `./mvnw spring-boot:run`, JDK _version_ |
| Tool | k6 _version_ |

## Procedure

1. `docker compose up -d` and start the app.
2. Create two accounts (note the returned `id`s; the funding account takes an early id, so don't assume 1 and 2) and fund the source generously:
   ```bash
   curl -s -XPOST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{}'
   curl -s -XPOST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{}'
   curl -s -XPOST localhost:8080/api/v1/accounts/$SRC/topup -H 'Content-Type: application/json' -d '{"amount": 10000000}'
   ```
3. Run: `SRC=$SRC DEST=$DEST k6 run --vus 50 --duration 60s load/payments_load.js`
4. Cross-check latency from the app's side: `GET /actuator/metrics/http.server.requests?tag=uri:/api/v1/payments`.

Disable fraud for throughput runs (`MPG_FRAUD_ENABLED=false`); otherwise the velocity rule holds most of a single-account burst and you're measuring the hold path.

## Results

| Stage | Config | Max sustainable TPS | P50 | P95 | P99 | Error rate | Notes |
|---|---|---|---|---|---|---|---|
| (a) naive | `MPG_RATELIMIT_ENABLED=false` | | | | | | |
| (b) locking tuned | `MPG_LOCKING_STRATEGY=` PESSIMISTIC vs OPTIMISTIC | | | | | | |
| (c) cache + rate limiter | defaults | | | | | | 429s counted separately (`rate_limited`) |
