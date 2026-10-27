package com.dixansh.mini_payment_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the per-API-key rate limiter. Bound from {@code mpg.ratelimit.*}.
 *
 * <p>The policy is two token-bucket bandwidths applied together (a request must have a token in
 * <em>both</em>): a <b>sustained</b> allowance (the headline "N per minute") and a tighter
 * <b>burst</b> allowance that stops a client from firing the whole minute's budget in one instant.
 * Example default: 100 requests/minute, with instantaneous bursts capped at 20/second.
 */
@Component
@ConfigurationProperties(prefix = "mpg.ratelimit")
public class RateLimitProperties {

    /** Master switch. When false the whole limiter (beans + filter) is not created. */
    private boolean enabled = true;

    /** Request header carrying the caller's API key; the bucket is keyed on its value. */
    private String apiKeyHeader = "X-Api-Key";

    /** Sustained allowance: {@code sustainedTokens} per {@code sustainedPeriodSeconds}. */
    private long sustainedTokens = 100;
    private long sustainedPeriodSeconds = 60;

    /** Burst guard: at most {@code burstTokens} per {@code burstPeriodSeconds}. */
    private long burstTokens = 20;
    private long burstPeriodSeconds = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public long getSustainedTokens() {
        return sustainedTokens;
    }

    public void setSustainedTokens(long sustainedTokens) {
        this.sustainedTokens = sustainedTokens;
    }

    public long getSustainedPeriodSeconds() {
        return sustainedPeriodSeconds;
    }

    public void setSustainedPeriodSeconds(long sustainedPeriodSeconds) {
        this.sustainedPeriodSeconds = sustainedPeriodSeconds;
    }

    public long getBurstTokens() {
        return burstTokens;
    }

    public void setBurstTokens(long burstTokens) {
        this.burstTokens = burstTokens;
    }

    public long getBurstPeriodSeconds() {
        return burstPeriodSeconds;
    }

    public void setBurstPeriodSeconds(long burstPeriodSeconds) {
        this.burstPeriodSeconds = burstPeriodSeconds;
    }
}
