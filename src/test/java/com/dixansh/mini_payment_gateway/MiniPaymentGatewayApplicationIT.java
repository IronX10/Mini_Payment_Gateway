package com.dixansh.mini_payment_gateway;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full context starts against the Testcontainers infrastructure (Flyway migrates,
 * Kafka topics are created, Redis connects). Lives with the other integration tests so plain
 * {@code mvn test} stays Docker-free.
 */
class MiniPaymentGatewayApplicationIT extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
