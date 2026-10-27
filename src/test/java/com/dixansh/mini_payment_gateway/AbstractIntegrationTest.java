package com.dixansh.mini_payment_gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Base for the Testcontainers integration tests: the whole app runs against REAL Postgres, Kafka and
 * Redis in disposable Docker containers, and is driven over real HTTP on a random port.
 *
 * <p>The containers use the <em>singleton</em> pattern - started once in a static block and shared by
 * every subclass - rather than {@code @Testcontainers}/{@code @Container}, which would stop them after
 * each test class and invalidate Spring's cached context. All subclasses share one property set, so
 * Spring also reuses a single application context across the suite.
 *
 * <p>Two Phase 4/5 guards are switched off here on purpose: the rate limiter (a 50-thread storm is
 * <em>meant</em> to exceed 20 req/s) and the fraud engine (a burst of payments would trip the velocity
 * rule and be HELD instead of settled). Both are covered by their own unit tests; these tests are
 * about the money path and the async pipeline. Webhook backoff is shrunk to seconds so retry and
 * dead-letter behaviour can be observed in a test run.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mpg.ratelimit.enabled=false",
                "mpg.fraud.enabled=false",
                "mpg.outbox.poll-ms=200",
                "mpg.webhook.retry-poll-ms=200",
                "mpg.webhook.max-attempts=4",
                "mpg.webhook.base-backoff-seconds=1",
                "mpg.webhook.backoff-multiplier=2",
                "mpg.webhook.max-backoff-seconds=2"
        })
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    /**
     * Redis is wired through the plain {@code spring.data.redis.*} properties rather than
     * {@code @ServiceConnection}: the rate limiter builds its own Lettuce client from those properties,
     * so this keeps every Redis consumer pointed at the same container.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    /** A raw HTTP response: status plus the parsed JSON body (or {@code null} when there is none). */
    protected record Response(int status, JsonNode body) {
    }

    // ---- HTTP helpers ------------------------------------------------------------------------

    protected Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    protected Response post(String path, Object body) {
        return post(path, body, null);
    }

    protected Response post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder.build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> res = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = res.body() == null || res.body().isBlank() ? null : JSON.readTree(res.body());
            return new Response(res.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling " + request.uri(), e);
        }
    }

    // ---- Domain helpers ----------------------------------------------------------------------

    /** Creates an INR account for a brand-new user, so every test works on isolated accounts. */
    protected JsonNode createAccount() {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        Response res = post("/api/v1/accounts", new CreateAccount(email, "INR"));
        if (res.status() != 201) {
            throw new IllegalStateException("account creation failed: " + res);
        }
        return res.body();
    }

    protected long createFundedAccount(String amount) {
        long id = createAccount().get("id").asLong();
        Response res = post("/api/v1/accounts/" + id + "/topup", new Topup(new BigDecimal(amount)));
        if (res.status() != 200) {
            throw new IllegalStateException("topup failed: " + res);
        }
        return id;
    }

    protected Response pay(String idempotencyKey, long source, long dest, String amount) {
        return post("/api/v1/payments", new Payment(source, dest, new BigDecimal(amount), "INR"), idempotencyKey);
    }

    /** The account's cached balance straight from the DB (bypasses the 5s read cache). */
    protected BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    protected record CreateAccount(String email, String currency) {
    }

    protected record Topup(BigDecimal amount) {
    }

    protected record Payment(Long source, Long dest, BigDecimal amount, String currency) {
    }
}
