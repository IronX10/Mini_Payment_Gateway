package com.dixansh.mini_payment_gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for expected, client-facing errors. Carries the HTTP status and a stable
 * machine-readable error code so {@code GlobalExceptionHandler} can render one consistent
 * JSON error shape across the whole API (modelled on Stripe's error envelope).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
