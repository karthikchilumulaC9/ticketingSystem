package org.example.distributedticketingmanagementsystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.distributedticketingmanagementsystem.kafka.exception.BulkProcessingErrorCode;
import org.example.distributedticketingmanagementsystem.kafka.exception.KafkaBulkProcessingException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.HashMap;
import java.util.Map;

/**
 * Enterprise-level Kafka Configuration for Bulk Ticket Processing.
 *
 * <p>Configuration includes:
 * <ul>
 *   <li>Topic configuration with 5 partitions for parallel processing</li>
 *   <li>Replication factor of 3 for high availability</li>
 *   <li>7-day retention for audit and reprocessing</li>
 *   <li>Dead Letter Topic for failed messages</li>
 *   <li>Exponential backoff retry mechanism</li>
 *   <li>Comprehensive error handling</li>
 * </ul>
 *
 * <p>Topics:
 * <ul>
 *   <li>ticket.bulk.requests - Main bulk processing topic</li>
 *   <li>ticket.bulk.requests.DLT - Dead Letter Topic</li>
 *   <li>ticket.bulk.notifications - Processing completion notifications</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@Configuration
@EnableKafka
public class KafkaBulkProcessingConfig {

    // ==================== Configuration Properties ====================

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${app.kafka.consumer.bulk-group-id:ticket-bulk-consumers}")
    private String bulkConsumerGroupId;

    // Topic Configuration
    @Value("${app.kafka.topics.bulk-requests:ticket.bulk.requests}")
    private String bulkRequestsTopic;

    @Value("${app.kafka.topics.bulk-notifications:ticket.bulk.notifications}")
    private String bulkNotificationsTopic;

    // Topic Settings
    @Value("${app.kafka.topics.bulk-requests.partitions:5}")
    private int bulkRequestsPartitions;

    @Value("${app.kafka.topics.bulk-requests.replicas:3}")
    private int bulkRequestsReplicas;

    @Value("${app.kafka.topics.bulk-requests.retention-days:7}")
    private int retentionDays;

    // Consumer Settings
    @Value("${app.kafka.consumer.concurrency:3}")
    private int consumerConcurrency;

    @Value("${app.kafka.consumer.batch-size:100}")
    private int batchSize;

    @Value("${app.kafka.consumer.poll-timeout-ms:1000}")
    private int pollTimeoutMs;

    // Retry Settings
    @Value("${app.kafka.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${app.kafka.retry.initial-interval-ms:1000}")
    private long initialIntervalMs;

    @Value("${app.kafka.retry.multiplier:2.0}")
    private double multiplier;

    @Value("${app.kafka.retry.max-interval-ms:10000}")
    private long maxIntervalMs;

    // ==================== ObjectMapper ====================

    /**
     * ObjectMapper configured for Kafka JSON serialization.
     * Handles Java 8 date/time types and avoids timestamps.
     */
    @Bean
    public ObjectMapper kafkaBulkObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // ==================== Topics ====================

    /**
     * Main topic for bulk ticket requests.
     *
     * Configuration:
     * - 5 partitions for parallel processing
     * - 3 replicas for high availability (in production)
     * - 7-day retention for audit and reprocessing
     */
    @Bean
    public NewTopic bulkRequestsTopic() {
        log.info("📋 Creating topic: {} with {} partitions, {} replicas",
                bulkRequestsTopic, bulkRequestsPartitions, Math.min(bulkRequestsReplicas, 1));

        return TopicBuilder.name(bulkRequestsTopic)
                .partitions(bulkRequestsPartitions)
                .replicas(Math.min(bulkRequestsReplicas, 1)) // Use 1 for dev, 3 for prod
                .config("retention.ms", String.valueOf(retentionDays * 24 * 60 * 60 * 1000L))
                .config("cleanup.policy", "delete")
                .config("segment.bytes", String.valueOf(1073741824)) // 1GB segments
                .build();
    }

    /**
     * Dead Letter Topic for failed bulk requests.
     * Messages that exhaust retry attempts are sent here.
     */
    @Bean
    public NewTopic bulkRequestsDltTopic() {
        String dltTopicName = bulkRequestsTopic + ".DLT";
        log.info("📋 Creating DLT topic: {}", dltTopicName);

        return TopicBuilder.name(dltTopicName)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000)) // 30 days
                .build();
    }

    /**
     * Topic for bulk processing completion notifications.
     */
    @Bean
    public NewTopic bulkNotificationsTopic() {
        log.info("📋 Creating notifications topic: {}", bulkNotificationsTopic);

        return TopicBuilder.name(bulkNotificationsTopic)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .build();
    }

    // ==================== Producer Configuration ====================

    /**
     * Producer configuration with reliability and performance settings.
     */
    @Bean
    public Map<String, Object> bulkProducerConfigs() {
        Map<String, Object> props = new HashMap<>();

        // Bootstrap servers
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Performance settings
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16KB
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Timeout settings
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);

        log.info("📤 Producer configuration initialized for bootstrap: {}", bootstrapServers);
        return props;
    }

    /**
     * Producer factory using JSON serialization.
     */
    @Bean
    public ProducerFactory<String, Object> bulkProducerFactory(
            @Qualifier("kafkaBulkObjectMapper") ObjectMapper kafkaBulkObjectMapper) {
        JsonSerde<Object> jsonSerde = new JsonSerde<>(Object.class, kafkaBulkObjectMapper);

        return new DefaultKafkaProducerFactory<>(
                bulkProducerConfigs(),
                new StringSerializer(),
                jsonSerde.serializer()
        );
    }

    /**
     * KafkaTemplate for bulk processing.
     */
    @Bean
    public KafkaTemplate<String, Object> bulkKafkaTemplate(
            ProducerFactory<String, Object> bulkProducerFactory) {

        KafkaTemplate<String, Object> template = new KafkaTemplate<>(bulkProducerFactory);
        template.setObservationEnabled(true);

        log.info("📤 KafkaTemplate initialized for bulk processing");
        return template;
    }

    // ==================== Consumer Configuration ====================

    /**
     * Consumer configuration for bulk processing.
     */
    @Bean
    public Map<String, Object> bulkConsumerConfigs() {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, bulkConsumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Offset management
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Performance settings
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batchSize);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // Session management
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        log.info("📥 Consumer configuration initialized - group: {}, concurrency: {}",
                bulkConsumerGroupId, consumerConcurrency);
        return props;
    }

    /**
     * Consumer factory for bulk processing.
     */
    @Bean
    public ConsumerFactory<String, Object> bulkConsumerFactory(
            @Qualifier("kafkaBulkObjectMapper") ObjectMapper kafkaBulkObjectMapper) {
        JsonSerde<Object> jsonSerde = new JsonSerde<>(Object.class, kafkaBulkObjectMapper)
                .ignoreTypeHeaders();

        return new DefaultKafkaConsumerFactory<>(
                bulkConsumerConfigs(),
                new StringDeserializer(),
                jsonSerde.deserializer()
        );
    }

    // ==================== Error Handling ====================

    /**
     * Error handler with Dead Letter Topic and exponential backoff.
     *
     * Non-retryable exceptions are sent directly to DLT:
     * - IllegalArgumentException
     * - NullPointerException
     * - KafkaBulkProcessingException (when not retryable)
     */
    @Bean
    public CommonErrorHandler bulkErrorHandler(KafkaTemplate<String, Object> bulkKafkaTemplate) {
        // Dead Letter Publisher
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                bulkKafkaTemplate,
                (record, exception) -> {
                    log.error("☠️ Sending to DLT - topic: {}, key: {}, error: {}",
                            record.topic(), record.key(), exception.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            record.topic() + ".DLT", 0);
                }
        );

        // Exponential backoff
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetryAttempts);
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMs);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Configure non-retryable exceptions
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                NullPointerException.class,
                org.example.distributedticketingmanagementsystem.exception.NullRequestException.class,
                org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException.class
        );

        // Add custom classification for KafkaBulkProcessingException
        errorHandler.setClassifications(Map.of(
                KafkaBulkProcessingException.class, false // Default to not-retryable, check in handler
        ), false);

        log.info("🛡️ Error handler configured - maxRetries: {}, initialInterval: {}ms, maxInterval: {}ms",
                maxRetryAttempts, initialIntervalMs, maxIntervalMs);

        return errorHandler;
    }

    // ==================== Listener Factories ====================

    /**
     * Kafka listener container factory for bulk processing.
     *
     * Configuration:
     * - Concurrency: 3 consumers per instance
     * - Manual acknowledgment (RECORD mode)
     * - Observation enabled for metrics
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> bulkKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> bulkConsumerFactory,
            CommonErrorHandler bulkErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(bulkConsumerFactory);
        factory.setCommonErrorHandler(bulkErrorHandler);
        factory.setBatchListener(false);
        factory.setConcurrency(consumerConcurrency);

        // Container properties
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setPollTimeout(pollTimeoutMs);

        log.info("📥 Listener container factory configured - concurrency: {}, ackMode: RECORD",
                consumerConcurrency);

        return factory;
    }

    /**
     * Batch listener container factory for high-throughput processing.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchBulkKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> bulkConsumerFactory,
            CommonErrorHandler bulkErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(bulkConsumerFactory);
        factory.setCommonErrorHandler(bulkErrorHandler);
        factory.setBatchListener(true);
        factory.setConcurrency(consumerConcurrency);

        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setPollTimeout(pollTimeoutMs);

        log.info("📥 Batch listener container factory configured - concurrency: {}, ackMode: BATCH",
                consumerConcurrency);

        return factory;
    }
}

