package com.dixansh.mini_payment_gateway.config;

import com.dixansh.mini_payment_gateway.web.RateLimitFilter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;

/**
 * Wires the Bucket4j-over-Redis rate limiter and registers its servlet filter. The entire block is
 * conditional on {@code mpg.ratelimit.enabled} (default true): when disabled - e.g. in a slice test
 * with no Redis - none of these beans and no filter exist, so the app starts clean.
 *
 * <p>A dedicated Lettuce {@link RedisClient} is created here rather than reusing Spring Data Redis's
 * connection factory, because Bucket4j's {@link LettuceBasedProxyManager} needs the native Lettuce
 * connection typed as {@code <String, byte[]>} (string keys, binary bucket state). It is a cheap,
 * long-lived, thread-safe client; Spring owns its lifecycle via the destroy methods below.
 */
@Configuration
@ConditionalOnProperty(prefix = "mpg.ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient() {
        return RedisClient.create(RedisURI.builder().withHost(redisHost).withPort(redisPort).build());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient rateLimitRedisClient) {
        // String keys so bucket names are human-readable in Redis; byte[] values hold Bucket4j's
        // serialized bucket state.
        return rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> rateLimitRedisConnection,
                                               RateLimitProperties properties) {
        // Idle buckets are evicted once enough time has passed for them to have fully refilled, so
        // Redis doesn't accumulate a key per API key forever.
        return LettuceBasedProxyManager.builderFor(rateLimitRedisConnection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofSeconds(properties.getSustainedPeriodSeconds())))
                .build();
    }

    /**
     * Registers the filter for {@code /api/*} only, at high precedence so it runs before Spring MVC
     * (and before any auth/business logic) - abusive traffic is rejected as early and cheaply as
     * possible. Internal, actuator and simulator endpoints are intentionally left unlimited.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(ProxyManager<String> rateLimitProxyManager,
                                                                        RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(rateLimitProxyManager, properties));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("rateLimitFilter");
        return registration;
    }
}
