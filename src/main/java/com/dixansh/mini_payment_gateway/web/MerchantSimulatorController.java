package com.dixansh.mini_payment_gateway.web;

import com.dixansh.mini_payment_gateway.util.HmacUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A stand-in "merchant" that receives our webhooks, so retries and signatures can be demoed against
 * the app itself without an external service. Enabled only under the {@code merchant-sim} profile.
 *
 * <p>It does three interview-relevant things:
 * <ol>
 *   <li>verifies {@code X-Signature} (HMAC-SHA256) and rejects a bad/missing one with 401;</li>
 *   <li>dedupes on {@code X-Webhook-Id}, proving the receiver copes with at-least-once redelivery;</li>
 *   <li>fails ~30% of the time (configurable) so you can watch backoff + retries actually happen.</li>
 * </ol>
 */
@RestController
@RequestMapping("/sim")
@Profile("merchant-sim")
public class MerchantSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(MerchantSimulatorController.class);

    /** Shared secret the demo registers its endpoint with; must match the endpoint's secret. */
    @Value("${mpg.merchant-sim.secret:demo-merchant-secret}")
    private String secret;

    /** Probability [0,1] that a well-formed, authentic webhook is rejected with 500 to force a retry. */
    @Value("${mpg.merchant-sim.fail-rate:0.30}")
    private double failRate;

    /** Event ids already processed - the receiver-side idempotency store (in-memory for the demo). */
    private final Set<String> seen = new HashSet<>();

    @PostMapping("/webhook")
    public ResponseEntity<String> receive(@RequestHeader(value = "X-Signature", required = false) String signature,
                                          @RequestHeader(value = "X-Webhook-Id", required = false) String webhookId,
                                          @RequestBody String body) {
        if (!HmacUtil.verify(secret, body, signature)) {
            log.warn("merchant-sim: rejected webhook {} - bad signature", webhookId);
            return ResponseEntity.status(401).body("invalid signature");
        }

        // At-least-once means duplicates are expected; a 200 on a duplicate is correct and idempotent.
        synchronized (seen) {
            if (webhookId != null && !seen.add(webhookId)) {
                log.info("merchant-sim: duplicate webhook {} ignored (already processed)", webhookId);
                return ResponseEntity.ok("duplicate ignored");
            }
        }

        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            // Roll back the "processed" mark so the retry can succeed later.
            synchronized (seen) {
                if (webhookId != null) {
                    seen.remove(webhookId);
                }
            }
            log.info("merchant-sim: simulated failure for webhook {} (will be retried)", webhookId);
            return ResponseEntity.status(500).body("simulated processing failure");
        }

        log.info("merchant-sim: accepted webhook {}", webhookId);
        return ResponseEntity.ok("ok");
    }
}
