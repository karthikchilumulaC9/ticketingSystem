package org.example.distributedticketingmanagementsystem.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represents the status of a bulk upload batch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Unique batch identifier.
     */
    private String batchId;

    /**
     * Current status: IN_PROGRESS, COMPLETED, FAILED.
     */
    private String status;

    /**
     * Total number of chunks in the batch.
     */
    private int totalChunks;

    /**
     * Number of chunks that have been processed.
     */
    private int completedChunks;

    /**
     * Total number of tickets in the batch.
     */
    private int totalTickets;

    /**
     * Number of successfully created tickets.
     */
    private int successCount;

    /**
     * Number of failed ticket creations.
     */
    private int failureCount;

    /**
     * When the batch processing started.
     */
    private LocalDateTime startTime;

    /**
     * When the batch processing completed.
     */
    private LocalDateTime endTime;

    /**
     * User who initiated the upload.
     */
    private String uploadedBy;

    /**
     * Original filename if uploaded from file.
     */
    private String originalFilename;

    /**
     * Calculate progress percentage.
     */
    public double getProgressPercentage() {
        if (totalChunks == 0) return 0;
        return (completedChunks * 100.0) / totalChunks;
    }

    /**
     * Check if batch is complete.
     */
    public boolean isComplete() {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    /**
     * Get processed count (success + failure).
     */
    public int getProcessedCount() {
        return successCount + failureCount;
    }
}

