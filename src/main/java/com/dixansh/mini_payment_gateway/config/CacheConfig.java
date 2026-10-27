package com.dixansh.mini_payment_gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.time.Duration;

/**
 * Redis-backed <b>cache-aside</b> for the hot read {@code GET /accounts/{id}} (see
 * {@code AccountService#getAccount}). A short TTL bounds staleness: an account's balance changes on
 * every payment, and payments don't run through {@code AccountService}, so the cache can't be
 * evicted on those writes - the TTL is the safety net. We deliberately keep it small (5s) and,
 * just as deliberately, leave the ledger endpoint <b>uncached</b> (it must always be authoritative).
 *
 * <p>Only {@code /accounts/{id}} is cached; writes we <em>do</em> own ({@code topup}) evict the key.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /** Cache name used by {@code @Cacheable}/{@code @CacheEvict} in AccountService. */
    public static final String ACCOUNTS_CACHE = "accounts";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${mpg.cache.account-ttl-seconds:5}")
    private long accountTtlSeconds;

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
        // Reuse Boot's JsonMapper (Jackson 3, the Boot 4 default: java.time and records work out of
        // the box), then add default typing so the concrete cached type is restored on read. The
        // validator is permissive because we only ever cache our own DTOs.
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer
                .builder(jsonMapper::rebuild)
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder().allowIfSubType(Object.class).build())
                .build();

        RedisCacheConfiguration accountsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(accountTtlSeconds))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(ACCOUNTS_CACHE, accountsConfig)
                .build();
    }

    /**
     * Fail-open cache errors: if Redis is unreachable, log and fall through to the underlying method
     * (a DB read) instead of surfacing a 500. Caching is an optimisation, not a dependency. Wired in
     * via {@link CachingConfigurer} so {@code @EnableCaching} actually applies it.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("cache GET failed on {} (key {}), falling back to source: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("cache PUT failed on {} (key {}): {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("cache EVICT failed on {} (key {}): {}", cache.getName(), key, exception.getMessage());
            }
        };
    }
}
