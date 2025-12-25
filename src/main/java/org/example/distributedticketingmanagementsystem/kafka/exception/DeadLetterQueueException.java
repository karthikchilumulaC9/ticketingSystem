package org.example.distributedticketingmanagementsystem.kafka.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Exception thrown when a Kafka message is sent to the Dead Letter Topic (DLT).
 * Contains detailed information about the failed message and failure context.
 *
 * <p>This exception is used to:
 * <ul>
 *   <li>Track messages that exhausted retry attempts</li>
 *   <li>Provide context for manual reprocessing</li>
 *   <li>Enable monitoring and alerting on DLT events</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Getter
public class DeadLetterQueueException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Original Kafka topic the message was sent to.
     */
    private final String originalTopic;

    /**
     * Kafka message key.
     */
    private final String messageKey;

    /**
     * Partition where the message was consumed from.
     */
    private final int partition;

    /**
     * Offset of the original message.
     */
    private final long offset;

    /**
     * Number of retry attempts before moving to DLT.
     */
    private final int retryAttempts;

    /**
     * Timestamp when the message was moved to DLT.
     */
    private final LocalDateTime dltTimestamp;

    /**
     * Error code classification.
     */
    private final BulkProcessingErrorCode errorCode;

    /**
     * Constructs a DeadLetterQueueException with full context.
     *
     * @param message        Error message
     * @param originalTopic  Original Kafka topic
     * @param messageKey     Kafka message key
     * @param partition      Kafka partition
     * @param offset         Message offset
     * @param retryAttempts  Number of retry attempts
     * @param errorCode      Error code classification
     * @param cause          Underlying cause
     */
    public DeadLetterQueueException(String message, String originalTopic, String messageKey,
                                     int partition, long offset, int retryAttempts,
                                     BulkProcessingErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.originalTopic = originalTopic;
        this.messageKey = messageKey;
        this.partition = partition;
        this.offset = offset;
        this.retryAttempts = retryAttempts;
        this.dltTimestamp = LocalDateTime.now();
        this.errorCode = errorCode != null ? errorCode : BulkProcessingErrorCode.SENT_TO_DLT;
    }

    /**
     * Constructs a simplified DeadLetterQueueException.
     */
    public DeadLetterQueueException(String message, String originalTopic, String messageKey, Throwable cause) {
        this(message, originalTopic, messageKey, -1, -1, 0,
             BulkProcessingErrorCode.fromException(cause), cause);
    }

    /**
     * Gets a detailed description for logging and monitoring.
     */
    public String getDetailedDescription() {
        return String.format(
                "[DLT] Topic: %s, Key: %s, Partition: %d, Offset: %d, " +
                "RetryAttempts: %d, ErrorCode: %s, Timestamp: %s - %s",
                originalTopic, messageKey, partition, offset,
                retryAttempts, errorCode.name(), dltTimestamp, getMessage()
        );
    }

    @Override
    public String toString() {
        return getDetailedDescription();
    }
}

