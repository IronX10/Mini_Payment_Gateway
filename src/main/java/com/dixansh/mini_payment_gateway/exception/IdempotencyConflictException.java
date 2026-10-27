package com.dixansh.mini_payment_gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 - an idempotent retry arrived while the original request is still PENDING (in flight).
 * The client should retry after a short delay rather than assume success or failure.
 */
public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "idempotency_conflict", message);
    }
}
