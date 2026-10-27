package com.dixansh.mini_payment_gateway.dto;

/**
 * Result of a create-payment call, carrying the raw JSON body plus the HTTP status the controller
 * should return: 201 for a freshly-executed payment, 200 for an idempotent replay of a stored one,
 * and 202 when the fraud engine held the payment for review (accepted, not yet settled).
 * The JSON is produced by the same serializer used for persistence, so a replay is byte-identical
 * to the original response.
 */
public record PaymentResult(int httpStatus, String json) {

    public static PaymentResult created(String json) {
        return new PaymentResult(201, json);
    }

    public static PaymentResult replayed(String json) {
        return new PaymentResult(200, json);
    }

    /** Payment accepted but held by the fraud engine pending manual review - HTTP 202. */
    public static PaymentResult held(String json) {
        return new PaymentResult(202, json);
    }
}
