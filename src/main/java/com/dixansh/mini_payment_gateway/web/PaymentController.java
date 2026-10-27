package com.dixansh.mini_payment_gateway.web;

import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.dto.CreatePaymentRequest;
import com.dixansh.mini_payment_gateway.dto.CursorPage;
import com.dixansh.mini_payment_gateway.dto.PaymentResponse;
import com.dixansh.mini_payment_gateway.dto.PaymentResult;
import com.dixansh.mini_payment_gateway.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Create a payment. Clients SHOULD send an {@code Idempotency-Key} header so retries are safe:
     * the key is authoritative (replay on repeat, 422 on body mismatch, 409 while in flight).
     * Returns 201 for a freshly-executed payment and 200 for an idempotent replay.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest req) {
        PaymentResult result = paymentService.createPayment(idempotencyKey, req);
        return ResponseEntity.status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.json());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return paymentService.getPayment(id);
    }

    @GetMapping
    public CursorPage<PaymentResponse> list(@RequestParam(required = false) TxStatus status,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return paymentService.listPayments(status, cursor, limit);
    }
}
