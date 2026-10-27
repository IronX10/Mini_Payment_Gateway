package com.dixansh.mini_payment_gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 422 - the same Idempotency-Key was reused with a <em>different</em> request body. That is almost
 * always a client bug (a key that isn't actually unique per logical operation), so we reject it
 * rather than silently replaying an unrelated result.
 */
public class IdempotencyKeyReuseException extends ApiException {
    public IdempotencyKeyReuseException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reuse", message);
    }
}
