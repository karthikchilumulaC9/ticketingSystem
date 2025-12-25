package org.example.distributedticketingmanagementsystem.kafka.exception;

import lombok.Getter;

/**
 * Exception thrown when Kafka bulk processing encounters an error.
 * This exception wraps the underlying cause and provides context about
 * the batch and chunk that failed.
 *
 * <p>Enterprise-level exception with:
 * <ul>
 *   <li>Batch and chunk identification for tracing</li>
 *   <li>Error code classification for automated handling</li>
 *   <li>Retryable flag for error recovery decisions</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Getter
public class KafkaBulkProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Unique batch identifier for tracking.
     */
    private final String batchId;

    /**
     * Chunk number that failed (0-based, -1 if not applicable).
     */
    private final int chunkNumber;

    /**
     * Error code for categorization.
     */
    private final BulkProcessingErrorCode errorCode;

    /**
     * Whether this error is retryable.
     */
    private final boolean retryable;

    /**
     * Number of affected records.
     */
    private final int affectedRecords;

    /**
     * Constructs a new KafkaBulkProcessingException with detailed context.
     *
     * @param message         Human-readable error message
     * @param batchId         Unique batch identifier
     * @param chunkNumber     Chunk number that failed (-1 if not applicable)
     * @param errorCode       Error code for categorization
     * @param retryable       Whether this error is retryable
     * @param affectedRecords Number of records affected
     * @param cause           The underlying cause of the exception
     */
    public KafkaBulkProcessingException(String message, String batchId, int chunkNumber,
                                         BulkProcessingErrorCode errorCode, boolean retryable,
                                         int affectedRecords, Throwable cause) {
        super(message, cause);
        this.batchId = batchId;
        this.chunkNumber = chunkNumber;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.affectedRecords = affectedRecords;
    }

    /**
     * Constructs a KafkaBulkProcessingException without cause.
     */
    public KafkaBulkProcessingException(String message, String batchId, int chunkNumber,
                                         BulkProcessingErrorCode errorCode, boolean retryable,
                                         int affectedRecords) {
        this(message, batchId, chunkNumber, errorCode, retryable, affectedRecords, null);
    }

    /**
     * Constructs a simplified KafkaBulkProcessingException.
     */
    public KafkaBulkProcessingException(String message, String batchId,
                                         BulkProcessingErrorCode errorCode) {
        this(message, batchId, -1, errorCode, false, 0, null);
    }

    /**
     * Constructs from a cause with auto-derived error code.
     */
    public KafkaBulkProcessingException(String message, String batchId, Throwable cause) {
        this(message, batchId, -1, BulkProcessingErrorCode.fromException(cause),
             BulkProcessingErrorCode.isRetryable(cause), 0, cause);
    }

    /**
     * Creates a formatted error message including all context.
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Batch: ").append(batchId);
        if (chunkNumber >= 0) {
            sb.append(", Chunk: ").append(chunkNumber);
        }
        sb.append(", ErrorCode: ").append(errorCode.name());
        sb.append(", Retryable: ").append(retryable);
        if (affectedRecords > 0) {
            sb.append(", AffectedRecords: ").append(affectedRecords);
        }
        sb.append("] ").append(getMessage());
        return sb.toString();
    }

    @Override
    public String toString() {
        return getDetailedMessage();
    }
}

