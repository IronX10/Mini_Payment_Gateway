package com.dixansh.mini_payment_gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 422 - the request is well-formed but cannot be applied as a payment: insufficient funds,
 * currency mismatch, or source == destination. Throwing this rolls back the transaction.
 */
public class UnprocessablePaymentException extends ApiException {
    public UnprocessablePaymentException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
