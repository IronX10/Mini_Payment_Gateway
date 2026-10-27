package com.dixansh.mini_payment_gateway.web;

import com.dixansh.mini_payment_gateway.domain.WebhookEndpoint;
import com.dixansh.mini_payment_gateway.dto.HeldPaymentResponse;
import com.dixansh.mini_payment_gateway.dto.PaymentResponse;
import com.dixansh.mini_payment_gateway.dto.ReconcileResponse;
import com.dixansh.mini_payment_gateway.dto.WebhookDeliveryResponse;
import com.dixansh.mini_payment_gateway.dto.WebhookEndpointRequest;
import com.dixansh.mini_payment_gateway.dto.WebhookEndpointResponse;
import com.dixansh.mini_payment_gateway.service.FraudReviewService;
import com.dixansh.mini_payment_gateway.service.ReconcileService;
import com.dixansh.mini_payment_gateway.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Operational / admin endpoints, deliberately kept off the public {@code /api/v1} surface:
 * reconciliation, webhook registration, inspecting deliveries that exhausted their retries, and the
 * Phase 5 held-payment review queue (list + release).
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ReconcileService reconcileService;
    private final WebhookService webhookService;
    private final FraudReviewService fraudReviewService;

    public InternalController(ReconcileService reconcileService,
                              WebhookService webhookService,
                              FraudReviewService fraudReviewService) {
        this.reconcileService = reconcileService;
        this.webhookService = webhookService;
        this.fraudReviewService = fraudReviewService;
    }

    /** Compares cached balances against ledger-derived balances and checks the zero-sum invariant. */
    @GetMapping("/reconcile")
    public ReconcileResponse reconcile() {
        return reconcileService.reconcile();
    }

    /** Registers a merchant webhook endpoint. The signing secret is stored, never echoed back. */
    @PostMapping("/webhooks/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointResponse registerEndpoint(@Valid @RequestBody WebhookEndpointRequest req) {
        WebhookEndpoint endpoint = webhookService.registerEndpoint(req.userId(), req.url(), req.secret());
        return WebhookEndpointResponse.from(endpoint);
    }

    /** Deliveries that exhausted their retries (DEAD) - the dead-letter admin view. */
    @GetMapping("/webhooks/failed")
    public List<WebhookDeliveryResponse> failedWebhooks() {
        return webhookService.failedDeliveries().stream()
                .map(WebhookDeliveryResponse::from)
                .toList();
    }

    /** The fraud review queue: payments the rules engine held, with their score and reasons. */
    @GetMapping("/held")
    public List<HeldPaymentResponse> heldPayments() {
        return fraudReviewService.listHeld();
    }

    /**
     * Approve and settle a held payment (HELD -&gt; COMPLETED), resuming the normal event flow.
     * Returns the completed payment. A 404 if the id is unknown, 422 if it isn't currently HELD or
     * can no longer settle (e.g. the source is now short of funds).
     */
    @PostMapping("/transactions/{id}/release")
    public PaymentResponse release(@PathVariable UUID id) {
        return fraudReviewService.release(id);
    }
}
