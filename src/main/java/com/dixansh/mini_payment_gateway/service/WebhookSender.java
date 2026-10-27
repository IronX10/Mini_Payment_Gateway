package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.WebhookProperties;
import com.dixansh.mini_payment_gateway.util.HmacUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Performs the actual HTTP POST of a webhook and reports the outcome. Kept free of any persistence
 * or scheduling so it is trivially unit-testable against a stub server.
 *
 * <p>Every request carries {@code X-Signature: sha256=HMAC-SHA256(secret, body)} so the merchant can
 * authenticate the call, plus {@code X-Webhook-Id} (the event id) so a merchant can dedupe on their
 * side - necessary because this pipeline is at-least-once.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final RestClient restClient;

    public WebhookSender(WebhookProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeoutMs());
        factory.setReadTimeout((int) properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * POSTs {@code body} to {@code url}. Never throws on an HTTP error status - a non-2xx is a normal
     * "failed, will retry" signal, and a connect/read timeout maps to a null status code.
     */
    public Outcome deliver(String url, String secret, String eventId, String body) {
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", HmacUtil.sign(secret, body))
                    .header("X-Webhook-Id", eventId)
                    .body(body)
                    .retrieve()
                    // Suppress RestClient's default throw-on-error so we can inspect the status ourselves.
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .toBodilessEntity();
            int code = response.getStatusCode().value();
            return new Outcome(response.getStatusCode().is2xxSuccessful(), code);
        } catch (Exception e) {
            // Timeout, connection refused, DNS failure, ... - no status code to record.
            log.debug("webhook POST to {} failed transport-level: {}", url, e.getMessage());
            return new Outcome(false, null);
        }
    }

    /** Result of one delivery attempt. {@code statusCode} is null when the request never got a response. */
    public record Outcome(boolean delivered, Integer statusCode) {
    }
}
