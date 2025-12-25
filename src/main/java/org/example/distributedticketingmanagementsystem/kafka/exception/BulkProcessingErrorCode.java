package org.example.distributedticketingmanagementsystem.kafka.exception;

import org.apache.kafka.common.KafkaException;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Error code enumeration for Kafka bulk processing operations.
 * Provides categorized error codes for:
 * <ul>
 *   <li>Validation errors</li>
 *   <li>Processing errors</li>
 *   <li>Infrastructure errors</li>
 *   <li>Kafka-specific errors</li>
 * </ul>
 *
 * <p>Each error code has associated metadata for automated error handling
 * and recovery strategies.
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
public enum BulkProcessingErrorCode {

    // ==================== Validation Errors (1xxx) ====================
    /**
     * CSV file is empty or contains no data.
     */
    EMPTY_FILE("V1001", "File is empty or contains no data", false),

    /**
     * CSV file format is invalid or corrupted.
     */
    INVALID_FILE_FORMAT("V1002", "Invalid file format", false),

    /**
     * Required CSV columns are missing.
     */
    MISSING_REQUIRED_COLUMNS("V1003", "Missing required columns in CSV", false),

    /**
     * CSV row data validation failed.
     */
    INVALID_ROW_DATA("V1004", "Invalid row data", false),

    /**
     * Ticket number is null or empty.
     */
    MISSING_TICKET_NUMBER("V1005", "Ticket number is required", false),

    /**
     * Customer ID is null or invalid.
     */
    INVALID_CUSTOMER_ID("V1006", "Invalid customer ID", false),

    /**
     * Title is null or empty.
     */
    MISSING_TITLE("V1007", "Title is required", false),

    /**
     * Request payload is null.
     */
    NULL_REQUEST("V1008", "Request payload is null", false),

    /**
     * Batch size exceeds maximum limit.
     */
    BATCH_SIZE_EXCEEDED("V1009", "Batch size exceeds maximum limit", false),

    // ==================== Processing Errors (2xxx) ====================
    /**
     * Duplicate ticket number detected.
     */
    DUPLICATE_TICKET("P2001", "Duplicate ticket number", false),

    /**
     * Ticket creation failed.
     */
    TICKET_CREATION_FAILED("P2002", "Failed to create ticket", true),

    /**
     * Chunk processing failed.
     */
    CHUNK_PROCESSING_FAILED("P2003", "Failed to process chunk", true),

    /**
     * Batch processing failed.
     */
    BATCH_PROCESSING_FAILED("P2004", "Failed to process batch", true),

    /**
     * Individual record processing failed.
     */
    RECORD_PROCESSING_FAILED("P2005", "Failed to process record", true),

    /**
     * Status transition validation failed.
     */
    INVALID_STATUS_TRANSITION("P2006", "Invalid status transition", false),

    /**
     * Priority value is invalid.
     */
    INVALID_PRIORITY("P2007", "Invalid priority value", false),

    // ==================== Infrastructure Errors (3xxx) ====================
    /**
     * Database connection or query error.
     */
    DATABASE_ERROR("I3001", "Database error", true),

    /**
     * Redis connection or operation error.
     */
    REDIS_ERROR("I3002", "Redis cache error", true),

    /**
     * I/O error during file processing.
     */
    IO_ERROR("I3003", "I/O error", true),

    /**
     * Connection timeout error.
     */
    TIMEOUT_ERROR("I3004", "Operation timeout", true),

    /**
     * Out of memory error.
     */
    MEMORY_ERROR("I3005", "Out of memory", false),

    // ==================== Kafka Errors (4xxx) ====================
    /**
     * Kafka producer error.
     */
    KAFKA_PRODUCER_ERROR("K4001", "Kafka producer error", true),

    /**
     * Kafka consumer error.
     */
    KAFKA_CONSUMER_ERROR("K4002", "Kafka consumer error", true),

    /**
     * Kafka serialization error.
     */
    KAFKA_SERIALIZATION_ERROR("K4003", "Kafka serialization error", false),

    /**
     * Kafka deserialization error.
     */
    KAFKA_DESERIALIZATION_ERROR("K4004", "Kafka deserialization error", false),

    /**
     * Kafka broker unavailable.
     */
    KAFKA_BROKER_UNAVAILABLE("K4005", "Kafka broker unavailable", true),

    /**
     * Kafka topic not found.
     */
    KAFKA_TOPIC_NOT_FOUND("K4006", "Kafka topic not found", false),

    /**
     * Message sent to Dead Letter Topic.
     */
    SENT_TO_DLT("K4007", "Message sent to Dead Letter Topic", false),

    /**
     * Kafka offset commit failed.
     */
    KAFKA_COMMIT_FAILED("K4008", "Failed to commit offset", true),

    // ==================== General Errors (9xxx) ====================
    /**
     * Unknown or unexpected error.
     */
    UNKNOWN_ERROR("E9001", "Unknown error occurred", true),

    /**
     * Internal system error.
     */
    INTERNAL_ERROR("E9002", "Internal system error", true),

    /**
     * Configuration error.
     */
    CONFIGURATION_ERROR("E9003", "Configuration error", false);

    private final String code;
    private final String description;
    private final boolean retryable;

    BulkProcessingErrorCode(String code, String description, boolean retryable) {
        this.code = code;
        this.description = description;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Determines the appropriate error code from an exception.
     *
     * @param throwable The exception to categorize
     * @return The appropriate error code
     */
    public static BulkProcessingErrorCode fromException(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN_ERROR;
        }

        // Check for specific exception types
        if (throwable instanceof DataAccessException) {
            return DATABASE_ERROR;
        }
        if (throwable instanceof IOException) {
            return IO_ERROR;
        }
        if (throwable instanceof TimeoutException) {
            return TIMEOUT_ERROR;
        }
        if (throwable instanceof OutOfMemoryError) {
            return MEMORY_ERROR;
        }
        if (throwable instanceof KafkaException) {
            return KAFKA_PRODUCER_ERROR;
        }
        if (throwable instanceof IllegalArgumentException) {
            return INVALID_ROW_DATA;
        }
        if (throwable instanceof NullPointerException) {
            return NULL_REQUEST;
        }

        // Check exception message for hints
        String message = throwable.getMessage();
        if (message != null) {
            message = message.toLowerCase();
            if (message.contains("duplicate")) {
                return DUPLICATE_TICKET;
            }
            if (message.contains("validation")) {
                return INVALID_ROW_DATA;
            }
            if (message.contains("timeout")) {
                return TIMEOUT_ERROR;
            }
            if (message.contains("redis")) {
                return REDIS_ERROR;
            }
            if (message.contains("kafka") || message.contains("broker")) {
                return KAFKA_BROKER_UNAVAILABLE;
            }
        }

        return UNKNOWN_ERROR;
    }

    /**
     * Determines if an exception is retryable.
     *
     * @param throwable The exception to check
     * @return true if the operation should be retried
     */
    public static boolean isRetryable(Throwable throwable) {
        return fromException(throwable).isRetryable();
    }

    @Override
    public String toString() {
        return String.format("%s (%s) - %s [retryable=%s]", name(), code, description, retryable);
    }
}

