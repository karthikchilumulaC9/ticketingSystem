package org.example.distributedticketingmanagementsystem.kafka.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.distributedticketingmanagementsystem.kafka.exception.BulkProcessingErrorCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive response DTO for Kafka bulk upload operations.
 * Provides detailed tracking of batch processing status, progress, and results.
 *
 * <p>Enterprise features:
 * <ul>
 *   <li>Thread-safe counters for concurrent processing</li>
 *   <li>Detailed error tracking with categorization</li>
 *   <li>Progress calculation and ETA estimation</li>
 *   <li>Chunk-level status tracking</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkUploadResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== Identification ====================

    /**
     * Unique batch identifier for tracking.
     */
    private String batchId;

    /**
     * Correlation ID for distributed tracing.
     */
    private String correlationId;

    /**
     * Original filename if uploaded from file.
     */
    private String originalFilename;

    /**
     * User who initiated the upload.
     */
    private String uploadedBy;

    // ==================== Status ====================

    /**
     * Current processing status.
     */
    private BulkUploadStatus status;

    /**
     * Human-readable status message.
     */
    private String statusMessage;

    // ==================== Counts (Thread-safe) ====================

    /**
     * Total number of tickets in the batch.
     */
    @Builder.Default
    private AtomicInteger totalRecords = new AtomicInteger(0);

    /**
     * Number of successfully processed tickets.
     */
    @Builder.Default
    private AtomicInteger successCount = new AtomicInteger(0);

    /**
     * Number of failed ticket creations.
     */
    @Builder.Default
    private AtomicInteger failureCount = new AtomicInteger(0);

    /**
     * Number of skipped records (e.g., duplicates).
     */
    @Builder.Default
    private AtomicInteger skippedCount = new AtomicInteger(0);

    // ==================== Chunk Tracking ====================

    /**
     * Total number of chunks.
     */
    private int totalChunks;

    /**
     * Number of completed chunks.
     */
    @Builder.Default
    private AtomicInteger completedChunks = new AtomicInteger(0);

    /**
     * Per-chunk status tracking.
     */
    @Builder.Default
    private Map<Integer, ChunkStatus> chunkStatuses = new ConcurrentHashMap<>();

    // ==================== Timestamps ====================

    /**
     * When the upload was accepted.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime acceptedAt;

    /**
     * When processing started.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processingStartedAt;

    /**
     * When processing completed.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    /**
     * Last update timestamp.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUpdatedAt;

    // ==================== Error Tracking ====================

    /**
     * List of failed record details.
     */
    @Builder.Default
    private List<FailedRecord> failedRecords = Collections.synchronizedList(new ArrayList<>());

    /**
     * Error summary by category.
     */
    @Builder.Default
    private Map<String, AtomicInteger> errorSummary = new ConcurrentHashMap<>();

    /**
     * Global error message if entire batch failed.
     */
    private String globalErrorMessage;

    /**
     * Error code for batch-level failure.
     */
    private BulkProcessingErrorCode errorCode;

    // ==================== URLs ====================

    /**
     * URL to check status.
     */
    private String statusUrl;

    /**
     * URL to get failures.
     */
    private String failuresUrl;

    // ==================== Computed Properties ====================

    /**
     * Gets the processed count (success + failure + skipped).
     */
    public int getProcessedCount() {
        return successCount.get() + failureCount.get() + skippedCount.get();
    }

    /**
     * Gets progress percentage.
     */
    public double getProgressPercentage() {
        int total = totalRecords.get();
        if (total == 0) return 0;
        return (getProcessedCount() * 100.0) / total;
    }

    /**
     * Gets chunk progress percentage.
     */
    public double getChunkProgressPercentage() {
        if (totalChunks == 0) return 0;
        return (completedChunks.get() * 100.0) / totalChunks;
    }

    /**
     * Gets success rate percentage.
     */
    public double getSuccessRate() {
        int processed = getProcessedCount();
        if (processed == 0) return 0;
        return (successCount.get() * 100.0) / processed;
    }

    /**
     * Checks if processing is complete.
     */
    public boolean isComplete() {
        return status == BulkUploadStatus.COMPLETED ||
               status == BulkUploadStatus.FAILED ||
               status == BulkUploadStatus.PARTIALLY_COMPLETED;
    }

    /**
     * Gets estimated time remaining in seconds.
     */
    public Long getEstimatedTimeRemaining() {
        if (processingStartedAt == null || getProcessedCount() == 0) {
            return null;
        }

        long elapsedSeconds = java.time.Duration.between(
                processingStartedAt, LocalDateTime.now()).getSeconds();
        int processed = getProcessedCount();
        int remaining = totalRecords.get() - processed;

        if (processed == 0) return null;

        double ratePerSecond = (double) processed / elapsedSeconds;
        return (long) (remaining / ratePerSecond);
    }

    // ==================== Thread-safe Operations ====================

    /**
     * Records a successful processing.
     */
    public void recordSuccess() {
        successCount.incrementAndGet();
        lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Records a failed processing.
     */
    public void recordFailure(String ticketNumber, String errorCode, String errorMessage) {
        failureCount.incrementAndGet();
        failedRecords.add(new FailedRecord(ticketNumber, errorCode, errorMessage,
                LocalDateTime.now()));
        errorSummary.computeIfAbsent(errorCode, k -> new AtomicInteger(0)).incrementAndGet();
        lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Records a skipped record.
     */
    public void recordSkipped(String ticketNumber, String reason) {
        skippedCount.incrementAndGet();
        lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Marks a chunk as completed.
     */
    public void completeChunk(int chunkNumber, int success, int failed) {
        completedChunks.incrementAndGet();
        chunkStatuses.put(chunkNumber, new ChunkStatus(chunkNumber, "COMPLETED",
                success, failed, LocalDateTime.now()));
        lastUpdatedAt = LocalDateTime.now();

        // Check if all chunks are complete
        if (completedChunks.get() >= totalChunks) {
            if (failureCount.get() == 0) {
                status = BulkUploadStatus.COMPLETED;
                statusMessage = "All records processed successfully";
            } else if (successCount.get() == 0) {
                status = BulkUploadStatus.FAILED;
                statusMessage = "All records failed to process";
            } else {
                status = BulkUploadStatus.PARTIALLY_COMPLETED;
                statusMessage = String.format("Processing completed with %d successes and %d failures",
                        successCount.get(), failureCount.get());
            }
            completedAt = LocalDateTime.now();
        }
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an ACCEPTED response.
     */
    public static BulkUploadResponse accepted(String batchId, int totalRecords,
                                               int totalChunks, String filename,
                                               String uploadedBy) {
        return BulkUploadResponse.builder()
                .batchId(batchId)
                .originalFilename(filename)
                .uploadedBy(uploadedBy)
                .status(BulkUploadStatus.ACCEPTED)
                .statusMessage("Bulk upload accepted for processing")
                .totalRecords(new AtomicInteger(totalRecords))
                .totalChunks(totalChunks)
                .acceptedAt(LocalDateTime.now())
                .lastUpdatedAt(LocalDateTime.now())
                .statusUrl("/api/tickets/bulk-kafka/status/" + batchId)
                .failuresUrl("/api/tickets/bulk-kafka/failures/" + batchId)
                .build();
    }

    /**
     * Creates an error response.
     */
    public static BulkUploadResponse error(String batchId, BulkProcessingErrorCode errorCode,
                                            String errorMessage) {
        return BulkUploadResponse.builder()
                .batchId(batchId)
                .status(BulkUploadStatus.FAILED)
                .statusMessage("Bulk upload failed")
                .errorCode(errorCode)
                .globalErrorMessage(errorMessage)
                .acceptedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .lastUpdatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== Nested Types ====================

    /**
     * Processing status enumeration.
     */
    public enum BulkUploadStatus {
        /**
         * Upload accepted, queued for processing.
         */
        ACCEPTED,

        /**
         * Validation in progress.
         */
        VALIDATING,

        /**
         * Processing in progress.
         */
        PROCESSING,

        /**
         * All records processed successfully.
         */
        COMPLETED,

        /**
         * Some records processed successfully.
         */
        PARTIALLY_COMPLETED,

        /**
         * All records failed.
         */
        FAILED,

        /**
         * Processing cancelled.
         */
        CANCELLED
    }

    /**
     * Represents a failed record.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedRecord implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String ticketNumber;
        private String errorCode;
        private String errorMessage;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime failedAt;
    }

    /**
     * Represents chunk processing status.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChunkStatus implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private int chunkNumber;
        private String status;
        private int successCount;
        private int failureCount;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime completedAt;
    }
}

