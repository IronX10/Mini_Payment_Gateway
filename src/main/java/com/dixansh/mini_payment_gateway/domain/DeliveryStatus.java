package com.dixansh.mini_payment_gateway.domain;

/**
 * Lifecycle of a single webhook delivery (one per event+endpoint). Maps to the Postgres native
 * enum {@code delivery_status}.
 *
 * <ul>
 *   <li>{@code PENDING}  - created, first attempt not yet made (or in progress);</li>
 *   <li>{@code DELIVERED} - the merchant returned 2xx; terminal, happy;</li>
 *   <li>{@code RETRYING} - last attempt failed, {@code next_retry_at} is set;</li>
 *   <li>{@code DEAD}     - retries exhausted; terminal, needs human attention.</li>
 * </ul>
 */
public enum DeliveryStatus {
    PENDING,
    DELIVERED,
    RETRYING,
    DEAD
}
