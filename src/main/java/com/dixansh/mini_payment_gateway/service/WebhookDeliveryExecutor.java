package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.KafkaTopicConfig;
import com.dixansh.mini_payment_gateway.config.WebhookProperties;
import com.dixansh.mini_payment_gateway.domain.Account;
import com.dixansh.mini_payment_gateway.domain.DeliveryStatus;
import com.dixansh.mini_payment_gateway.domain.OutboxEvent;
import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.WebhookDelivery;
import com.dixansh.mini_payment_gateway.domain.WebhookEndpoint;
import com.dixansh.mini_payment_gateway.repository.AccountRepository;
import com.dixansh.mini_payment_gateway.repository.OutboxEventRepository;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import com.dixansh.mini_payment_gateway.repository.WebhookDeliveryRepository;
import com.dixansh.mini_payment_gateway.repository.WebhookEndpointRepository;
import com.dixansh.mini_payment_gateway.util.BackoffCalculator;
import com.dixansh.mini_payment_gateway.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The transactional units of webhook delivery, kept in their own bean so each runs behind a real
 * proxy (a self-invocation from the orchestrating {@link WebhookService} would silently skip the
 * transaction - the same trap avoided in Phase 2).
 *
 * <p>{@link #deliverOneDue()} claims exactly one due row with {@code FOR UPDATE SKIP LOCKED} and
 * does the HTTP POST while that single row is locked. Locking one row for the duration of one
 * bounded (timeout-capped) call is a deliberate simplicity trade-off: it needs no "in-flight" lease
 * state or reaper. At high webhook volume you'd instead lease rows (UPDATE ... RETURNING) and POST
 * outside the transaction; noted in DECISIONS.md.
 */
@Service
public class WebhookDeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryExecutor.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public WebhookDeliveryExecutor(WebhookDeliveryRepository deliveryRepository,
                                   WebhookEndpointRepository endpointRepository,
                                   OutboxEventRepository outboxEventRepository,
                                   TransactionRepository transactionRepository,
                                   AccountRepository accountRepository,
                                   WebhookSender sender,
                                   WebhookProperties properties,
                                   KafkaTemplate<String, String> kafkaTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.sender = sender;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * The webhook targets for an event: the endpoints of the merchant who <em>received</em> the
     * money (the destination account's owner). Resolving from the aggregate keeps the outbox payload
     * free of routing concerns.
     */
    @Transactional(readOnly = true)
    public List<WebhookEndpoint> endpointsForAggregate(UUID aggregateId) {
        Optional<Transaction> tx = transactionRepository.findById(aggregateId);
        if (tx.isEmpty()) {
            return List.of();
        }
        Optional<Account> dest = accountRepository.findById(tx.get().getDestAccount());
        if (dest.isEmpty()) {
            return List.of();
        }
        return endpointRepository.findByUserIdAndActiveTrue(dest.get().getUserId());
    }

    /**
     * Creates the (event, endpoint) delivery row if it doesn't already exist. The UNIQUE constraint
     * is the dedupe gate: a redelivered Kafka message (at-least-once) finds the row present and this
     * is a no-op. New rows are marked due immediately so the first attempt happens on the next drain.
     */
    @Transactional
    public void createIfAbsent(UUID eventId, Long endpointId) {
        if (deliveryRepository.existsByEventIdAndEndpointId(eventId, endpointId)) {
            return;
        }
        WebhookDelivery delivery = new WebhookDelivery(eventId, endpointId);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setNextRetryAt(Instant.now());
        try {
            deliveryRepository.saveAndFlush(delivery);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent consumer created the same row first; the dedupe still holds.
            log.debug("delivery row for event {} endpoint {} already created concurrently", eventId, endpointId);
        }
    }

    /**
     * Claims and processes a single due delivery. Returns {@code false} when nothing is due, so the
     * caller can stop draining.
     */
    @Transactional
    public boolean deliverOneDue() {
        List<WebhookDelivery> due = deliveryRepository.claimDue(Instant.now(), 1);
        if (due.isEmpty()) {
            return false;
        }
        attempt(due.get(0));
        return true;
    }

    private void attempt(WebhookDelivery delivery) {
        OutboxEvent event = outboxEventRepository.findById(delivery.getEventId()).orElse(null);
        WebhookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);

        if (event == null || endpoint == null || !endpoint.isActive()) {
            // Nothing deliverable (event or endpoint vanished); stop retrying this row.
            delivery.setStatus(DeliveryStatus.DEAD);
            delivery.setNextRetryAt(null);
            return;
        }

        String body = rebuildBody(event);
        delivery.setAttempt(delivery.getAttempt() + 1);

        WebhookSender.Outcome outcome = sender.deliver(
                endpoint.getUrl(), endpoint.getSecret(), event.getId().toString(), body);
        delivery.setLastResponseCode(outcome.statusCode());

        if (outcome.delivered()) {
            delivery.setStatus(DeliveryStatus.DELIVERED);
            delivery.setNextRetryAt(null);
            return;
        }
        if (delivery.getAttempt() >= properties.getMaxAttempts()) {
            delivery.setStatus(DeliveryStatus.DEAD);
            delivery.setNextRetryAt(null);
            publishToDlt(event, body);
            log.warn("webhook delivery {} DEAD after {} attempts (event {}, endpoint {})",
                    delivery.getId(), delivery.getAttempt(), event.getId(), endpoint.getId());
            return;
        }
        delivery.setStatus(DeliveryStatus.RETRYING);
        delivery.setNextRetryAt(nextRetryAt(delivery.getAttempt()));
    }

    /**
     * Rebuilds the exact envelope the relay published, from the durable outbox row. Because every
     * field is stable (event id, type, created-at, payload), a retry re-sends a byte-identical body -
     * so the HMAC signature is stable and a merchant deduping on event id sees the same event.
     */
    private String rebuildBody(OutboxEvent event) {
        return JsonUtil.envelope(
                event.getId().toString(),
                event.getEventType(),
                event.getAggregateId().toString(),
                event.getCreatedAt().toString(),
                event.getPayload());
    }

    /**
     * Exponential backoff with equal jitter, hard-capped at {@code maxBackoffSeconds}. The base
     * (un-jittered) schedule is attempt 1 -> 1m, 2 -> 5m, 3 -> 25m, ... Equal jitter (AWS-style)
     * keeps half the delay fixed and randomises the other half, so a burst of simultaneous failures
     * doesn't retry in lockstep (thundering herd) while retries never bunch up at zero. Because the
     * jitter is drawn from the already-capped delay, the result stays within
     * {@code [cap/2, cap]} and never exceeds the configured maximum.
     *
     * <p>The formula lives in {@link BackoffCalculator} so it can be unit-tested with a seeded RNG;
     * here we just feed it the configured schedule and the thread-local randomness source.
     */
    private Instant nextRetryAt(int attempt) {
        long delay = BackoffCalculator.equalJitterSeconds(
                attempt,
                properties.getBaseBackoffSeconds(),
                properties.getBackoffMultiplier(),
                properties.getMaxBackoffSeconds(),
                ThreadLocalRandom.current());
        return Instant.now().plusSeconds(delay);
    }

    private void publishToDlt(OutboxEvent event, String body) {
        try {
            // Best-effort: a dead delivery is already recorded in the DB; the DLT is a convenience
            // stream for ops tooling, so we don't fail (or roll back) the transaction if it can't send.
            kafkaTemplate.send(KafkaTopicConfig.PAYMENT_EVENTS_DLT, event.getAggregateId().toString(), body);
        } catch (Exception e) {
            log.warn("failed to mirror dead delivery of event {} to DLT: {}", event.getId(), e.getMessage());
        }
    }
}
