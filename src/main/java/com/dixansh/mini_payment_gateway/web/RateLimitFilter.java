package com.dixansh.mini_payment_gateway.web;

import com.dixansh.mini_payment_gateway.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Rejects callers who exceed their token-bucket allowance with a {@code 429 Too Many Requests}.
 * Because the bucket state lives in Redis (via Bucket4j's {@link ProxyManager}), the limit is
 * enforced across every app instance, not per-JVM - the whole point of a distributed limiter.
 *
 * <p>On every response it sets {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}; on a
 * rejection it adds {@code Retry-After} (seconds until a token frees up). The bucket is keyed on the
 * caller's API key; requests with no key fall back to the client IP so they can't sidestep limiting.
 *
 * <p><b>Fail-open:</b> if Redis is unreachable the request is allowed through. A rate limiter is a
 * guardrail, not a gate - it must never be the reason the gateway stops taking payments.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ProxyManager<String> proxyManager;
    private final RateLimitProperties properties;
    private final Supplier<BucketConfiguration> configurationSupplier;

    public RateLimitFilter(ProxyManager<String> proxyManager, RateLimitProperties properties) {
        this.proxyManager = proxyManager;
        this.properties = properties;
        // Built once and reused: the policy is static, only the key varies per request.
        this.configurationSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.getSustainedTokens())
                        .refillGreedy(properties.getSustainedTokens(),
                                Duration.ofSeconds(properties.getSustainedPeriodSeconds()))
                        .build())
                .addLimit(Bandwidth.builder()
                        .capacity(properties.getBurstTokens())
                        .refillGreedy(properties.getBurstTokens(),
                                Duration.ofSeconds(properties.getBurstPeriodSeconds()))
                        .build())
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        try {
            Bucket bucket = proxyManager.builder().build(key, configurationSupplier);
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            response.setHeader("X-RateLimit-Limit", Long.toString(properties.getSustainedTokens()));
            if (probe.isConsumed()) {
                response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
                chain.doFilter(request, response);
            } else {
                long retryAfterSeconds = Math.max(1,
                        (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
                response.setHeader("X-RateLimit-Remaining", "0");
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
                writeTooManyRequests(response, retryAfterSeconds);
            }
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception redisUnavailable) {
            // Fail-open: never let a limiter outage stop the gateway from serving traffic.
            log.warn("rate limiter unavailable, allowing request through: {}", redisUnavailable.getMessage());
            chain.doFilter(request, response);
        }
    }

    /** API key if present, else the client IP - so an anonymous flood is still bounded. */
    private String resolveKey(HttpServletRequest request) {
        String apiKey = request.getHeader(properties.getApiKeyHeader());
        if (apiKey != null && !apiKey.isBlank()) {
            return "rl:key:" + apiKey.trim();
        }
        return "rl:ip:" + request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Matches GlobalExceptionHandler's {"error","message"} shape. Written directly because a
        // servlet filter runs outside Spring MVC, so @RestControllerAdvice never sees it.
        String body = "{\"error\":\"rate_limited\","
                + "\"message\":\"rate limit exceeded; retry after " + retryAfterSeconds + "s\"}";
        response.getWriter().write(body);
    }
}
