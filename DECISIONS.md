# DECISIONS.md

The "why" behind each design choice, written as an interview script. Each entry is a decision, the alternatives, and the trade-off — the deep-dive is where this project gets cashed in.

---

## Phase 1 — Core domain & the double-entry ledger

### Why a double-entry ledger instead of a mutable balance column
A wallet stores one `balance` and mutates it. A payment system records **immutable ledger entries** — every transfer writes one `DEBIT` and one `CREDIT` of equal amount — and treats the balance as a *derived, cached* value. This buys three things:

- **An audit trail.** Nothing is ever overwritten; corrections are compensating entries.
- **A testable invariant.** `SUM(signed amount)` across the whole ledger is always exactly **zero**. If it isn't, money was created or destroyed — a bug you can *detect*.
- **Reconciliation.** The cached `accounts.balance` can be checked against the ledger at any time (`GET /internal/reconcile`).

### Why `BigDecimal`, never `double`
Binary floating point cannot represent `0.10` exactly, so `0.1 + 0.2 != 0.3`. Money uses `BigDecimal` end-to-end with `NUMERIC(18,2)` in Postgres. The interview follow-up ("explain floating-point rounding") is the whole point of the choice.

### Where money comes from — the settlement/funding account
Every ledger entry must be balanced, so money can't just appear when you top up. A top-up is modelled as a **transfer from a per-currency system "funding" account** (a settlement float) to the customer. That keeps top-ups balanced double-entry pairs and preserves the zero-sum invariant. The funding account is seeded with a large float and is *excluded from customer reconciliation* — it represents the gateway's own position, exactly like a bank's nostro account. This is why top-up reuses the exact same `executeTransfer` code path as a payment.

### One `@Transactional` unit = atomicity
`executeTransfer` does everything — insert the transaction, write both ledger entries, move both cached balances, mark `COMPLETED` — inside a single `@Transactional` method. Any exception (e.g. insufficient funds) rolls back *all* of it. That is atomicity, and the CHECK constraint `balance >= 0` is defence-in-depth in case application logic ever slips.

### Keyset (cursor) pagination, not OFFSET
The ledger and payments lists page by a cursor (`WHERE id < :cursor ORDER BY id DESC`) backed by an index, so page N costs the same as page 1. `OFFSET` re-scans and discards every skipped row, degrading linearly on deep pages — unacceptable for a statement endpoint.

### Foreign keys as plain ids, not `@ManyToOne`
Entities store `sourceAccount` / `accountId` as `Long` rather than JPA associations. It keeps hot-path queries explicit and sidesteps lazy-loading / N+1 surprises. `open-in-view` is disabled for the same reason.

### DTOs at the edge, entities never exposed
Requests/responses are validated records (`@NotNull`, `@Positive`, currency pattern). Entities never cross the HTTP boundary. The persisted `response_body` is hand-serialized so an idempotent replay (Phase 2) is byte-stable regardless of the web layer's JSON library.

---

## Phase 2 — Idempotency & concurrency control

Two *separate* problems, two *separate* mechanisms. Conflating them is the most common mistake here: idempotency stops the **same** request from executing twice; concurrency control stops **different** requests from corrupting a shared balance.

### Idempotency: rely on the UNIQUE constraint, not check-then-insert
`transactions.idempotency_key` is `UNIQUE`. The write path **inserts first** and lets the database arbitrate:

- **Insert succeeds** -> first time we've seen this key; do the transfer.
- **Insert violates the constraint** -> someone else owns this key. Re-read the row and either replay its stored `response_body` or signal that it's still in flight.

A check-then-insert (`SELECT` then `INSERT`) has a race window: two concurrent duplicates both see "not found" and both proceed. The constraint has no such window — under a truly simultaneous duplicate the second `INSERT` *blocks* on the unique index until the first transaction commits, then surfaces cleanly as a violation. That is precisely why exactly one of two parallel duplicates performs the transfer while the other replays.

The status/body contract the client sees:

- **Same key + same body, original terminal** -> replay the stored response, **200** (vs **201** for a fresh execution, so a caller can tell a replay from a first run).
- **Same key + different body** (compared via `request_hash`, a canonical SHA-256 of source|dest|amount|currency) -> **422** `idempotency_key_reuse`. Almost always a client bug — silently replaying the *old* response would hide it.
- **Same key, original still `PENDING`** -> **409** `idempotency_conflict`, retry later.

### Why the orchestration lives *outside* the transaction
Reacting to a constraint violation means catching it *after* the offending transaction has rolled back, then reading again in a **fresh** transaction. So `PaymentService.createPayment` is deliberately **not** `@Transactional`; the atomic unit is `TransferService.executeTransfer`, a separate bean with its own proxy. Calling a `@Transactional` method on the *same* bean would self-invoke and skip the proxy entirely — the classic Spring trap — so the split into two beans is load-bearing, not cosmetic. The orchestrator's `saveAndFlush` forces the duplicate-key `INSERT` to surface as a `DataIntegrityViolationException` *inside* the try block, where it's translated into a replay.

### Concurrency: the lost-update bug, then two fixes
Without locking, two parallel debits of 800 from an account holding 1000 can *both* pass the funds check on the same stale read and drive the balance negative — a lost update.

- **Pessimistic (default):** `SELECT ... FOR UPDATE` on both account rows, always acquired in **ascending id order**. The consistent ordering is what stops `A->B` and `B->A` transfers from deadlocking. Wins under hot-account contention: no retry storms, one waiter simply queues behind the lock.
- **Optimistic (behind `mpg.locking.strategy`):** a `@Version` column; on commit a concurrent write raises `OptimisticLockingFailureException`, and the orchestrator retries with exponential backoff + jitter (capped by `mpg.locking.maxRetries`, then **409** `concurrent_update`). Wins under *low* contention: no locks held, no waiting.

Having both implemented — and being able to say *when each wins* — is the strong signal. Note the idempotency-key uniqueness handles dedup identically in both modes; the strategy flag only governs how the **account** rows are guarded. The `balance >= 0` CHECK constraint is the final backstop under either strategy.

---

## Phase 3 — Async processing: transactional outbox, Kafka, webhooks

The goal: when a payment settles, tell the merchant — reliably, without coupling the payment's success to the merchant's server being up. Three sub-problems chain together: (1) record the event atomically with the payment, (2) publish it to Kafka exactly-once-ish, (3) deliver it over HTTP with retries. Each has a specific failure mode the naive version gets wrong.

### The transactional outbox: why not just publish after commit
The tempting code is `savePayment(); kafka.send(event);`. It has a **dual-write** bug: the two systems (Postgres and Kafka) can't share a transaction. If the process dies between the commit and the send, the payment exists but the event is **lost forever** — the merchant is never told money arrived. Flip the order (send *then* commit) and you get the opposite: an event for a payment that then **rolled back** — a phantom.

The outbox fixes this by making the event part of the **same transaction** as the payment. `TransferService.applyTransfer` writes an `outbox_events` row in the very same `@Transactional` unit that moves the balances and writes the ledger. Either both commit or neither does — there is no window. To enforce that it can never be called outside a transaction, `OutboxService.record` is `@Transactional(propagation = MANDATORY)`: if no transaction is active it throws, turning a latent bug into a loud one.

A separate **poller** (`OutboxPoller` → `OutboxRelay.publishBatch`) then reads unsent rows and publishes them to Kafka on its own schedule. Publishing is now decoupled from the payment: a Kafka outage delays delivery but never loses an event, because the durable source of truth is the outbox table, not the broker.

> Production alternative I'd name: **Debezium / CDC** tailing the Postgres WAL instead of polling. It's lower-latency and puts no read load on the table, but it's a whole piece of infra (Kafka Connect). Polling with `SKIP LOCKED` is the right call at this scale and demonstrates the pattern without the operational weight.

### `FOR UPDATE SKIP LOCKED`: horizontal scaling with zero coordination
Both claim queries (`OutboxEventRepository.claimBatch`, `WebhookDeliveryRepository.claimDue`) end with `FOR UPDATE SKIP LOCKED`. This is the key to running **multiple poller instances** safely: each `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n` grabs a *disjoint* batch — locked rows are skipped rather than waited on — so two app replicas never process the same event and never block each other. No distributed lock, no leader election, no partitioning scheme. (Postgres requires the locking clause to come *last*, after `ORDER BY`/`LIMIT`; getting that order wrong is a silent runtime error, so it's worth calling out.)

### Metadata in Kafka headers, body rebuilt from the outbox
The consumer never parses the message body to route it. Event id, event type, and aggregate id ride in **Kafka headers**, and the **key** is the aggregate (transaction) id — which also gives us per-aggregate ordering within a partition. The body is rebuilt from the durable outbox row on every (re)delivery via `JsonUtil.envelope`, so a retry re-sends a **byte-identical** payload. That byte-stability matters: it means the HMAC signature is identical across retries and a merchant deduping on event id sees the same bytes every time.

### At-least-once, so dedupe is the consumer's job
Kafka + a retrying poller give **at-least-once** delivery: a rebalance or a crash between "sent" and "marked SENT" can replay an event. Exactly-once across HTTP to arbitrary merchant servers is a fiction, so instead of chasing it the design **embraces at-least-once and makes redelivery a no-op**:

- Internally, `webhook_deliveries` has `UNIQUE (event_id, endpoint_id)`. `createIfAbsent` inserts one delivery row per (event, endpoint); a replayed event finds the row already present and does nothing. A concurrent double-insert is caught as a `DataIntegrityViolationException` — the constraint, not a check-then-insert, is the arbiter (same principle as Phase 2 idempotency).
- Externally, every webhook carries `X-Webhook-Id` (the event id) so the *merchant* can dedupe on their side too.

### Splitting orchestration from the transactional unit (again)
Delivering a batch of webhooks means doing **HTTP inside a loop** — and you must never hold a database transaction open across a network call to someone else's server (a slow merchant would pin a connection and a row lock for seconds). So the same two-bean split as Phase 2 recurs, for the same proxy reason plus a new one:

- `WebhookService` — the orchestrator — is **not** `@Transactional`. It loops.
- `WebhookDeliveryExecutor.deliverOneDue` is `@Transactional` and handles **exactly one** row: claim it with `SKIP LOCKED`, POST it, record the outcome, commit. The lock is held only for one bounded (timeout-capped) HTTP call.

Calling one `@Transactional` method from another method of the *same* bean would self-invoke and bypass the proxy — so the split is load-bearing. (At very high volume you'd lease rows with `UPDATE ... RETURNING` and POST fully outside any transaction; noted as the next step.)

### Retry with exponential backoff + equal jitter, then a dead-letter
A failed delivery (non-2xx, or a connect/read timeout → null status) is normal, not exceptional. `WebhookSender` suppresses RestClient's throw-on-error so a 500 is just data. The executor then schedules the next attempt with **exponential backoff and equal jitter**: base × multiplier^(attempt−1), hard-capped at `maxBackoffSeconds`, with half the delay fixed and half randomised. The jitter matters at scale — without it, a fleet of merchants that all failed during one outage would retry in **lockstep** and hammer everything back down (thundering herd). After `maxAttempts` the row goes `DEAD`, stops retrying, and is mirrored to a **dead-letter topic** (`payment.events.dlt`) for ops tooling. DLT publishing is best-effort: the dead state is already durably recorded in Postgres, so a DLT send failure never rolls back the transaction.

### Timeouts are mandatory, not optional
`WebhookSender` sets explicit connect (2s) and read (3s) timeouts. A webhook target is an untrusted third-party server; without a read timeout a single hung merchant would tie up a worker thread indefinitely and, because that thread holds a row lock for the duration, starve the whole retry pipeline. Bounded timeouts are what make "lock one row across the HTTP call" a safe simplification rather than a liability.

### Routing: events resolve to the *recipient* merchant
The outbox payload stays free of routing concerns. At delivery time `endpointsForAggregate` resolves transaction → destination account → owning user → that user's active webhook endpoints. The webhook goes to whoever **received** the money. This also cleanly explains why a dev **top-up emits no event**: `executeTransfer` takes an `eventType` that `PaymentService` sets to `payment.completed` but `AccountService.topup` passes as `null` — an internal ledger movement from the funding float isn't a merchant payment and mustn't fire a webhook.

## Phase 4 — Rate limiting & caching with Redis

Two independent problems that both want a fast, shared, out-of-process data store, so both land on Redis: (1) stop any single caller from overwhelming the gateway, enforced *across all app instances*; (2) take read load off Postgres for a hot, rarely-changing read. Redis is the right tool for each because the state is small, shared, and can be lost without hurting correctness — which also dictates the one rule that governs everything here: **fail-open**.

### Token bucket, not fixed or sliding window
The limiter is a **token bucket** (`Bucket4j`), not a fixed-window counter. A fixed window (`N` per calendar minute) has a well-known flaw: a client can send `N` at 12:00:59 and another `N` at 12:01:00 — `2N` in two seconds — because the counter resets on a boundary it doesn't control. A token bucket has no boundary: tokens refill *continuously* (`refillGreedy`), so the rate is smooth and the only thing a client can ever bank is the bucket's capacity. It also models the thing we actually want — a **steady rate with a bounded burst** — in one primitive.

I apply **two bandwidths at once** and a request must have a token in both: a *sustained* allowance (the headline "100/minute") and a tighter *burst* allowance ("20/second"). The sustained limit sets the long-run rate; the burst limit stops a client from spending the whole minute's budget in a single 50ms spike. One bandwidth alone can't express "100/min but never more than 20 at once."

> Alternative I'd name: a **sliding-window-log** (sorted set of timestamps) is the most precise, but it stores every request timestamp and is far heavier. Token bucket gives you burst-shaping essentially for free and is what Stripe/GitHub-style limiters actually use.

### Distributed by construction, not per-JVM
The bucket state lives in Redis via Bucket4j's `ProxyManager` (`RateLimitConfig`), not in an in-memory `ConcurrentHashMap`. This is the entire reason to reach for Redis here: with two app replicas behind a load balancer, a per-JVM limiter would let a client do `2N` by spreading requests across instances. A single shared bucket in Redis means the limit is the limit no matter how many replicas serve the traffic. Idle buckets self-evict (`ExpirationAfterWriteStrategy` tied to the refill time), so Redis doesn't accumulate a key per API key forever.

### The filter runs first, and only on `/api/*`
Rate limiting is a `OncePerRequestFilter` registered at `HIGHEST_PRECEDENCE + 10` (`RateLimitConfig.rateLimitFilterRegistration`), so abusive traffic is rejected *before* Spring MVC, auth, or any business logic touches it — rejection should be the cheapest path in the system. It's scoped to `/api/*` only: internal admin, actuator health, and the webhook simulator are deliberately left unlimited. The bucket is keyed on the caller's API key (`X-Api-Key`); a request with no key falls back to the client IP, so an anonymous flood is still bounded and can't sidestep the limiter by simply omitting the header. A rejection returns `429` with `Retry-After` and `X-RateLimit-*` headers, written as raw JSON in the `{"error","message"}` shape — because a filter runs outside MVC, `@RestControllerAdvice` never sees it.

### Fail-open everywhere, on purpose
Every Redis touchpoint in this phase swallows failure and proceeds as if the store weren't there. The limiter catches any Redis error and lets the request through (`RateLimitFilter` — "a guardrail, not a gate"); the cache uses a `CacheErrorHandler` that logs and falls back to the database; the velocity counter returns `0` on error. This is a value judgement made explicit: a Redis outage must **never** be the reason the gateway stops taking payments. We accept that during an outage the rate limit isn't enforced and the cache is cold — both degrade *availability of the optimisation*, not *correctness of the money*. (Contrast Postgres, where the opposite is true: a DB failure *must* fail the payment.)

### Cache-aside for the hot read, TTL as the staleness bound
`GET /accounts/{id}` is cached with Spring's `@Cacheable` (`AccountService.getAccount`, `CacheConfig`), classic **cache-aside**: on a hit the method body and its DB transaction never run. The subtlety is invalidation. An account's balance changes on **every payment**, and payments don't flow through `AccountService` — they go through `TransferService` writing the ledger directly — so there's no clean write path on which to evict the key. Rather than sprinkle brittle eviction calls across the payment path, the design leans on a **short TTL (5s)** as the staleness bound: a cached balance can be at most a few seconds stale, which is fine for a display read and never used for a money decision. The one write we *do* own, `topup`, evicts the key eagerly (`@CacheEvict`) so a manual cash-in shows up immediately; the TTL is the safety net for everything else.

Two things stay deliberately **uncached**: the **ledger** endpoint (it must always be authoritative — you never serve a stale list of money movements) and, obviously, any write. Caching the balance but not the ledger is the intended asymmetry: one is a hint, the other is the truth.

Serializing records through Redis needs care: the cache `ObjectMapper` is Boot's mapper *plus* default typing (`activateDefaultTyping`), so the concrete DTO type is written into the JSON and restored on read — without it, a cached record deserializes as a `LinkedHashMap` and blows up on the cast.

### Velocity counters: cheap, atomic, self-expiring
`VelocityService` counts payments per account per minute with the classic `INCR tx_count:{account}:{minute}` then `EXPIRE 60` on the first increment. It's atomic (Redis is single-threaded per key), needs no cleanup job (the key expires itself), and the minute is encoded in the key so windows never overlap. This is built in Phase 4 but exists **for Phase 5**: it's the "too many payments in a short window" signal the fraud engine reads. It counts only *fresh* executions in `PaymentService`, never idempotent replays — a retried request is the same payment, not a new one, and must not inflate the velocity.

### A note on Bucket4j and Lettuce 7
This project runs on the Spring Boot 4.x line, which resolves **Lettuce 7.5.2**; Bucket4j 8.10.1's `LettuceBasedProxyManager` was built against Lettuce 6.x. Because Bucket4j drives Lettuce through its low-level `dispatch(ProtocolKeyword, CommandOutput, CommandArgs)` API, a signature change across the major version would surface as a runtime `NoSuchMethodError`, not a compile error. I verified against the resolved `lettuce-core-7.5.2` that the exact surface Bucket4j uses is intact — `dispatch(...)`, `RedisClient.create(RedisURI)`, `connect(RedisCodec)`, `StringCodec.UTF8`, `ByteArrayCodec.INSTANCE` — so the pairing should work. If a future Lettuce bump ever does break it, the whole limiter is isolated behind `mpg.ratelimit.enabled` and one config class, and the fallback is a hand-rolled Redis Lua token bucket (single atomic script) — the integration is swappable without touching the filter or the rest of the app.

## Phase 5 — Fraud-flagging hook

The goal: catch suspicious payments *before* they settle, hold them for a human to review, and be able to explain exactly why each one was held. Two design commitments drive everything: the engine is **rules-first** (deterministic and testable, not a black-box model), and it runs on a **strict latency budget** that fails open — risk scoring is never allowed to slow down or break the act of taking a payment.

### Rules before ML
Each rule is a small, deterministic predicate that contributes a weight to a running score; if the total reaches the threshold, the payment is held. Four rules ship (`FraudService`): the amount exceeds the account's **30-day P95** (once there's enough history for a baseline), **velocity** — more than N payments in the current minute (the Redis counter built in Phase 4), **first-time payee** — never successfully paid this destination before, and a **round-amount-at-midnight** combination. The score and the list of rule codes that fired are persisted on the transaction (`risk_score`, `risk_reasons`), so a held payment is always **auditable** — a reviewer sees *why*, not just *that*, it was held.

Rules come before ML on purpose, and it's the interview point behind the AMEX tie-in: rules are explainable to a regulator and a customer, unit-testable with known inputs, and shippable today; a model is a later *addition* (blend `0.5*rules + 0.5*model`), not a prerequisite. The weights are chosen so a single strong signal (a clear burst) holds on its own, while softer signals only hold when they **corroborate** — a large payment *to a brand-new payee* holds; a large payment to a well-known payee doesn't. All weights and thresholds are config (`mpg.fraud.*`), so sensitivity is tunable without a redeploy.

> On the AMEX angle interviewers probe — *why accuracy is misleading on fraud data*: fraud is hugely imbalanced (well under 1% of transactions), so a model that predicts "never fraud" scores >99% accuracy and catches nothing. You optimise precision/recall (or AUC-PR), not accuracy. The rules engine sidesteps this entirely by being a policy, not a fitted classifier.

### Hold means "not yet settled", and that shapes the whole flow
A `HELD` payment **has not moved money**. This is the key modelling decision: rather than settle and then try to claw back, a flagged payment is quarantined *before* the ledger is touched. `PaymentService` scores first, then branches — an allowed payment goes down the normal `executeTransfer` path (move balances, write the ledger pair, emit `payment.completed`); a held payment goes to a new `holdTransfer` that inserts the row as `HELD` with its risk score and emits `payment.held`, but writes **no ledger entries and moves no balance**. The client gets `202 Accepted` (accepted, pending review) rather than `201`.

Settlement is deferred to **release**. `POST /internal/transactions/{id}/release` calls `TransferService.releaseHeld`, which performs exactly the work `holdTransfer` skipped: lock the accounts, validate, move the money, write the ledger, mark `COMPLETED`, and emit `payment.completed` — resuming the normal event flow. Because validation runs *at release time* against current balances, a release can legitimately fail (the source may no longer have the funds), in which case the unit rolls back and the payment stays `HELD` for a retry after top-up. This is the human-in-the-loop the roadmap asks for, and it reuses the same locking/validation/outbox machinery as a normal transfer rather than duplicating it. Idempotency still holds throughout: `holdTransfer` inserts under the same UNIQUE idempotency key, so a retried held payment replays its stored `HELD` body just like any other.

### Synchronous scoring, but on a hard latency budget with default-allow
Scoring runs **synchronously** inside the payment path — the decision must be made before we choose to settle or hold — but it is capped at a strict wall-clock budget (`mpg.fraud.budget-millis`, default **50ms**). It runs on a small worker pool and the request thread waits at most the budget via `Future.get(timeout)`; on timeout, or on *any* error in a rule, the engine **defaults to allow** and logs. This is the latency-vs-risk trade-off stated explicitly: we would rather occasionally let a risky payment through — and catch it out-of-band — than make risk scoring a source of latency or a new way for payments to fail. It mirrors the fail-open stance of Phase 4: the money path's **availability** wins. A `Future.get` with a timeout genuinely *bounds* the latency the payment pays (a bare elapsed-time check would only detect an overrun after already paying for it). The cost I'd name in review: under sustained overload, timed-out scoring queries can orphan DB work; a production version would give scoring its own bounded connection pool or a circuit breaker.

### Where the counter is read vs. written
The velocity rule *reads* the current minute's count (a non-incrementing `GET`) at scoring time, and `PaymentService` *records* the attempt (`INCR`) only after the decision — for **both** allowed and held payments, because both are attempts by the account and a burst of held attempts should still escalate. Idempotent replays never reach the record step, so a retried payment is never double-counted. This read-then-record ordering is what lets a burst of N payments trip the velocity rule on payment N+1.

## Phase 6 — Testing

The goal: a green suite that *proves* the correctness claims made in Phases 1–5, rather than restating them. `./mvnw test` runs the fast, Docker-free unit tests; `./mvnw verify` adds the Testcontainers integration tests, and CI (`.github/workflows/ci.yml`) runs `verify`.

### Unit tests for logic, real infrastructure for guarantees
Branch logic is unit-tested with Mockito and no Spring context: the idempotency/fraud gating in `PaymentService`, every fraud rule, the HMAC signer, and the backoff formula (with a seeded RNG). But the guarantees that matter here — atomicity, the UNIQUE-constraint idempotency gate, row locking, `SKIP LOCKED`, at-least-once delivery — are properties of **Postgres and Kafka**, not of my code. Mocking them would test my assumptions about them. So those are proven against real Postgres 16, Kafka and Redis in disposable containers, driven over real HTTP. H2 is deliberately not used: it doesn't have native enums, `jsonb`, `FOR UPDATE SKIP LOCKED` or the same locking semantics, so it would make the tests that matter most meaningless.

### Singleton containers, one Spring context
`AbstractIntegrationTest` starts the containers once in a static block and every IT class shares them, rather than using `@Testcontainers`/`@Container` per class (which stops containers after each class and breaks Spring's context cache). All ITs share one property set, so Spring builds one application context for the whole suite. Each test creates its own fresh accounts instead of truncating tables, so tests don't depend on order and the global reconciliation check still has to hold across everything that ran before.

### Assert invariants, not interleavings
The concurrency tests (`TransferStormIT`: 50 threads of random transfers in both directions across 5 accounts; `PaymentFlowIT`: 20 threads racing one Idempotency-Key) use a fixed pool released together by a `CountDownLatch` to maximise collisions, then assert only what must *always* hold: money is conserved, the ledger sums to zero, no balance goes negative, cached balances match the ledger, exactly one execution per key, and no 5xx (a deadlock from bad lock ordering would surface as one). Asserting a particular interleaving would just make the test flaky.

### Simulating the outbox crash window
The dangerous window is "Kafka acked, but the SENT flag never committed". `OutboxRecoveryIT` reproduces it exactly by flipping a delivered row back to `NEW`, then proves both halves of at-least-once: an independent consumer sees the event on the topic **twice**, yet the merchant is notified **once**, because the (event, endpoint) UNIQUE constraint absorbs the duplicate. `WebhookDeliveryIT` uses a JDK `HttpServer` stub merchant that fails on demand to prove retry-on-500 with byte-identical, validly signed redeliveries, and the dead-letter path once `max-attempts` is exhausted. Backoff is shrunk to seconds in the test properties so this runs in test time without changing the production formula.

### What the ITs switch off, and why
The rate limiter and the fraud engine are disabled in the integration context: the storm is *supposed* to exceed 20 req/s, and a burst of payments would trip the velocity rule and be held rather than settled. Both have their own unit tests; the ITs are about the money path and the async pipeline. One integration detail caught by this phase: Spring Boot 4 brings Jackson 3 (`tools.jackson`) and Testcontainers 2 (modules renamed `testcontainers-*`, `PostgreSQLContainer` moved to `org.testcontainers.postgresql`), so the Redis cache serializer was moved to Spring Data Redis's Jackson 3 `GenericJacksonJsonRedisSerializer`.
