package org.example.distributedticketingmanagementsystem.kafka.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Exception thrown when Kafka message consumption fails.
 * Contains detailed context about the failed message consumption.
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Getter
public class KafkaConsumerException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Source Kafka topic.
     */
    private final String topic;

    /**
     * Partition number.
     */
    private final int partition;

    /**
     * Message offset.
     */
    private final long offset;

    /**
     * Message key.
     */
    private final String messageKey;

    /**
     * Consumer group ID.
     */
    private final String consumerGroup;

    /**
     * Error code classification.
     */
    private final BulkProcessingErrorCode errorCode;

    /**
     * Timestamp of the failure.
     */
    private final LocalDateTime failureTimestamp;

    /**
     * Number of processing attempts.
     */
    private final int attemptNumber;

    /**
     * Whether retry is recommended.
     */
    private final boolean retryable;

    /**
     * Constructs a KafkaConsumerException with full context.
     *
     * @param message       Error message
     * @param topic         Source Kafka topic
     * @param partition     Partition number
     * @param offset        Message offset
     * @param messageKey    Message key
     * @param consumerGroup Consumer group ID
     * @param attemptNumber Current attempt number
     * @param cause         Underlying cause
     */
    public KafkaConsumerException(String message, String topic, int partition, long offset,
                                   String messageKey, String consumerGroup,
                                   int attemptNumber, Throwable cause) {
        super(message, cause);
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.messageKey = messageKey;
        this.consumerGroup = consumerGroup;
        this.attemptNumber = attemptNumber;
        this.errorCode = BulkProcessingErrorCode.fromException(cause);
        this.failureTimestamp = LocalDateTime.now();
        this.retryable = BulkProcessingErrorCode.isRetryable(cause);
    }

    /**
     * Constructs a simplified KafkaConsumerException.
     */
    public KafkaConsumerException(String message, String topic, int partition,
                                   long offset, Throwable cause) {
        this(message, topic, partition, offset, null, null, 1, cause);
    }

    /**
     * Gets a detailed error description.
     */
    public String getDetailedDescription() {
        return String.format(
                "[KafkaConsumerError] Topic: %s, Partition: %d, Offset: %d, Key: %s, " +
                "Group: %s, Attempt: %d, Retryable: %s, ErrorCode: %s, Timestamp: %s - %s",
                topic, partition, offset, messageKey, consumerGroup,
                attemptNumber, retryable, errorCode.name(), failureTimestamp, getMessage()
        );
    }

    /**
     * Determines if this message should be sent to DLT.
     */
    public boolean shouldSendToDlt() {
        return !retryable || attemptNumber >= 3;
    }

    @Override
    public String toString() {
        return getDetailedDescription();
    }
}

