package com.dixansh.mini_payment_gateway.exception;

import org.springframework.http.HttpStatus;

/** 404 - a referenced account, payment, or user does not exist. */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "not_found", message);
    }
}
