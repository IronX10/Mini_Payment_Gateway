package com.dixansh.mini_payment_gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-drives webhook deliveries whose backoff has elapsed. The inline drain right after consuming
 * handles the happy path; this scheduler is what actually implements <em>retry</em> - it catches
 * rows left {@code RETRYING} by a failed merchant, and {@code PENDING} rows orphaned by a crash
 * between "row created" and "first attempt".
 */
@Component
public class WebhookRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryScheduler.class);

    private final WebhookService webhookService;

    public WebhookRetryScheduler(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Scheduled(fixedDelayString = "${mpg.webhook.retry-poll-ms:1000}")
    public void retryDue() {
        try {
            int processed = webhookService.drainDue();
            if (processed > 0) {
                log.debug("webhook retry scheduler processed {} due delivery(ies)", processed);
            }
        } catch (Exception e) {
            // A bad tick must never kill the scheduler; due rows survive for the next run.
            log.warn("webhook retry tick failed, will retry: {}", e.getMessage());
        }
    }
}
