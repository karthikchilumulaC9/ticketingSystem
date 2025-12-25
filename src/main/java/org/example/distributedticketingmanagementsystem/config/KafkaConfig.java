package org.example.distributedticketingmanagementsystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
 * Kafka Configuration for the Distributed Ticketing Management System.
 * Based on Spring Kafka 3.x/4.x best practices:
 * - Uses @EnableKafka for annotation-driven listeners
 * - Configures producer and consumer factories with proper serialization
 * - Implements Dead Letter Topic (DLT) for failed message handling
 * - Uses exponential backoff for retries
 *
 * Topics:
 * - ticket-events: Main topic for ticket creation events
 * - ticket-events.DLT: Dead Letter Topic for failed messages
 * - ticket-bulk-upload: Topic for bulk upload processing
 * - ticket-bulk-upload.DLT: DLT for bulk upload failures
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:ticketing-group}")
    private String groupId;

    @Value("${app.kafka.topics.ticket-events:ticket-events}")
    private String ticketEventsTopic;

    @Value("${app.kafka.topics.bulk-upload:ticket-bulk-upload}")
    private String bulkUploadTopic;

    @Value("${app.kafka.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${app.kafka.retry.initial-interval-ms:1000}")
    private long initialIntervalMs;

    @Value("${app.kafka.retry.multiplier:2.0}")
    private double multiplier;

    @Value("${app.kafka.retry.max-interval-ms:10000}")
    private long maxIntervalMs;

    // ==================== ObjectMapper for JSON ====================

    /**
     * ObjectMapper configured for Kafka JSON serialization.
     */
    @Bean
    public ObjectMapper kafkaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    // ==================== Topics ====================

    /**
     * Main topic for ticket events (create, update, delete).
     */
    @Bean
    public NewTopic ticketEventsTopic() {
        return TopicBuilder.name(ticketEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Dead Letter Topic for failed ticket events.
     */
    @Bean
    public NewTopic ticketEventsDltTopic() {
        return TopicBuilder.name(ticketEventsTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Topic for bulk ticket upload processing.
     */
    @Bean
    public NewTopic bulkUploadTopic() {
        return TopicBuilder.name(bulkUploadTopic)
                .partitions(6)
                .replicas(1)
                .build();
    }

    /**
     * Dead Letter Topic for failed bulk uploads.
     */
    @Bean
    public NewTopic bulkUploadDltTopic() {
        return TopicBuilder.name(bulkUploadTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    // ==================== Producer Configuration ====================

    /**
     * Producer configuration map.
     * Uses JsonSerde for serialization (Spring Kafka 4.0+ recommended).
     */
    @Bean
    public Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Value serializer will be set via ProducerFactory

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Performance settings
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        return props;
    }

    /**
     * Producer factory using JsonSerde for JSON serialization.
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory(ObjectMapper kafkaObjectMapper) {
        JsonSerde<Object> jsonSerde = new JsonSerde<>(Object.class, kafkaObjectMapper);
        return new DefaultKafkaProducerFactory<>(
                producerConfigs(),
                new StringSerializer(),
                jsonSerde.serializer()
        );
    }

    /**
     * KafkaTemplate for sending messages.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        return template;
    }

    // ==================== Consumer Configuration ====================

    /**
     * Consumer configuration map.
     */
    @Bean
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Value deserializer will be set via ConsumerFactory

        // Auto offset reset strategy
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Disable auto-commit for manual acknowledgment
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return props;
    }

    /**
     * Consumer factory using JsonSerde for JSON deserialization.
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory(ObjectMapper kafkaObjectMapper) {
        JsonSerde<Object> jsonSerde = new JsonSerde<>(Object.class, kafkaObjectMapper)
                .ignoreTypeHeaders();

        return new DefaultKafkaConsumerFactory<>(
                consumerConfigs(),
                new StringDeserializer(),
                jsonSerde.deserializer()
        );
    }

    /**
     * Error handler with Dead Letter Topic publishing and exponential backoff retry.
     */
    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetryAttempts);
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMs);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                NullPointerException.class
        );

        return errorHandler;
    }

    /**
     * Kafka listener container factory for consuming messages.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            CommonErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(false);
        factory.setConcurrency(3);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);

        return factory;
    }

    /**
     * Batch listener container factory for bulk processing.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            CommonErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(true);
        factory.setConcurrency(6);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);

        return factory;
    }
}

