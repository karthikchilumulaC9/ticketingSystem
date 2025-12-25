package org.example.distributedticketingmanagementsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;

import java.time.Duration;

/**
 * Redis Configuration for caching.
 * Uses Spring Data Redis 4.0+ recommended configuration.
 *
 * Caching Strategy:
 * - GET /tickets/{id}: Check Redis cache with key "ticket:{id}"
 * - Cache hit: Return cached data
 * - Cache miss: Query MySQL → Store in Redis → Return data
 *
 * Cache Configuration:
 * - Redis connection pooling via Lettuce
 * - Serialization: JSON format using RedisSerializer.json()
 * - TTL: 30 minutes (configurable)
 * - Eviction policy: LRU (configured on Redis server)
 */
@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.timeout:60000}")
    private long timeout;

    @Value("${app.cache.ttl-minutes:30}")
    private long cacheTtlMinutes;

    /**
     * Configure client resources for Lettuce.
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.create();
    }

    /**
     * Configure Redis connection factory with connection pooling.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .build();

        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .clientResources(clientResources)
                .clientOptions(clientOptions)
                .build();

        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }

    /**
     * Configure JSON serializer for Redis.
     * Spring Data Redis 4.0+ uses RedisSerializer.json() which returns a
     * properly configured JSON serializer with type information.
     */
    @Bean
    public RedisSerializer<Object> jsonRedisSerializer() {
        return RedisSerializer.json();
    }

    /**
     * Configure RedisTemplate for direct Redis operations.
     * Uses JSON serialization for values.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                        RedisSerializer<Object> jsonRedisSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);

        // Use JSON serializer for values
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Configure CacheManager with Redis backend.
     * Per Spring Data Redis 4.0+ documentation:
     * - Uses RedisCacheConfiguration for cache defaults
     * - TTL configured via entryTtl()
     * - Null values disabled to prevent caching nulls
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     RedisSerializer<Object> jsonRedisSerializer) {
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(cacheTtlMinutes))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonRedisSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .transactionAware()
                .build();
    }
}
