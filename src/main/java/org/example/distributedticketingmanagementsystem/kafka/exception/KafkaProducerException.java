package org.example.distributedticketingmanagementsystem.kafka.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Exception thrown when Kafka message production fails.
 * Contains detailed context about the failed message and delivery attempt.
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Getter
public class KafkaProducerException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Target Kafka topic.
     */
    private final String topic;

    /**
     * Message key.
     */
    private final String messageKey;

    /**
     * Correlation ID for tracing.
     */
    private final String correlationId;

    /**
     * Error code classification.
     */
    private final BulkProcessingErrorCode errorCode;

    /**
     * Timestamp of the failure.
     */
    private final LocalDateTime failureTimestamp;

    /**
     * Whether retry is recommended.
     */
    private final boolean retryable;

    /**
     * Constructs a KafkaProducerException with full context.
     *
     * @param message       Error message
     * @param topic         Target Kafka topic
     * @param messageKey    Message key
     * @param correlationId Correlation ID
     * @param cause         Underlying cause
     */
    public KafkaProducerException(String message, String topic, String messageKey,
                                   String correlationId, Throwable cause) {
        super(message, cause);
        this.topic = topic;
        this.messageKey = messageKey;
        this.correlationId = correlationId;
        this.errorCode = BulkProcessingErrorCode.KAFKA_PRODUCER_ERROR;
        this.failureTimestamp = LocalDateTime.now();
        this.retryable = BulkProcessingErrorCode.isRetryable(cause);
    }

    /**
     * Constructs a simplified KafkaProducerException.
     */
    public KafkaProducerException(String message, String topic, Throwable cause) {
        this(message, topic, null, null, cause);
    }

    /**
     * Gets a detailed error description.
     */
    public String getDetailedDescription() {
        return String.format(
                "[KafkaProducerError] Topic: %s, Key: %s, CorrelationId: %s, " +
                "Retryable: %s, Timestamp: %s - %s",
                topic, messageKey, correlationId, retryable, failureTimestamp, getMessage()
        );
    }

    @Override
    public String toString() {
        return getDetailedDescription();
    }
}

