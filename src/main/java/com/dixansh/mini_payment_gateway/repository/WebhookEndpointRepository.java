package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    /** Active endpoints for a merchant - the fan-out target when one of their payments settles. */
    List<WebhookEndpoint> findByUserIdAndActiveTrue(Long userId);
}
