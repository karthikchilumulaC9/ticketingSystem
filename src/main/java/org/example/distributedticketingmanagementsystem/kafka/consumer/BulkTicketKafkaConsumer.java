package org.example.distributedticketingmanagementsystem.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException;
import org.example.distributedticketingmanagementsystem.exception.InvalidTicketOperationException;
import org.example.distributedticketingmanagementsystem.exception.NullRequestException;
import org.example.distributedticketingmanagementsystem.exception.TicketNotFoundException;
import org.example.distributedticketingmanagementsystem.kafka.dto.BatchStatus;
import org.example.distributedticketingmanagementsystem.kafka.dto.BulkUploadResponse;
import org.example.distributedticketingmanagementsystem.kafka.event.BulkTicketUploadEvent;
import org.example.distributedticketingmanagementsystem.kafka.exception.*;
import org.example.distributedticketingmanagementsystem.kafka.service.BulkUploadTrackingService;
import org.example.distributedticketingmanagementsystem.service.TicketService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.LocalDateTime;

/**
 * Enterprise-level Kafka Consumer for bulk ticket processing.
 * Implements comprehensive exception handling with categorized error responses.
 *
 * <p>Exception Handling Strategy:
 * <ul>
 *   <li><b>Non-retryable errors:</b> Acknowledge and record failure</li>
 *   <li><b>Retryable errors:</b> Re-throw for Kafka error handler</li>
 *   <li><b>Validation errors:</b> Acknowledge and skip</li>
 *   <li><b>Duplicate errors:</b> Acknowledge as already processed</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li>Topic: ticket.bulk.requests (configurable)</li>
 *   <li>Partitions: 5 for parallel processing</li>
 *   <li>Consumer Group: ticket-bulk-consumers</li>
 *   <li>Concurrency: 3 consumers per instance</li>
 *   <li>Batch size: 100 records</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkTicketKafkaConsumer {

    private final TicketService ticketService;
    private final BulkUploadTrackingService trackingService;

    // Processing statistics
    private static final ThreadLocal<ChunkProcessingContext> processingContext =
            ThreadLocal.withInitial(ChunkProcessingContext::new);

    /**
     * Consumes and processes bulk ticket upload events from Kafka.
     *
     * <p>Processing flow for each chunk:
     * <ol>
     *   <li>Validate chunk event</li>
     *   <li>Initialize tracking</li>
     *   <li>Process each ticket with individual error handling</li>
     *   <li>Update chunk status</li>
     *   <li>Acknowledge message</li>
     * </ol>
     *
     * @param event          The bulk upload event containing tickets
     * @param key            Kafka message key (chunk key)
     * @param partition      Partition number
     * @param offset         Message offset
     * @param timestamp      Message timestamp
     * @param acknowledgment Manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${app.kafka.topics.bulk-requests:ticket.bulk.requests}",
            groupId = "${app.kafka.consumer.bulk-group-id:ticket-bulk-consumers}",
            containerFactory = "kafkaListenerContainerFactory",
            concurrency = "3"
    )
    public void consumeBulkTicketEvent(
            @Payload BulkTicketUploadEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
            Acknowledgment acknowledgment) {

        // Initialize processing context
        ChunkProcessingContext context = processingContext.get();
        context.reset();
        context.setBatchId(event.getBatchId());
        context.setChunkNumber(event.getChunkNumber());
        context.setStartTime(LocalDateTime.now());

        log.info("📥 BULK CHUNK RECEIVED - batch: {}, chunk: {}/{}, tickets: {}, " +
                 "partition: {}, offset: {}, key: {}",
                event.getBatchId(),
                event.getChunkNumber() + 1,
                event.getTotalChunks(),
                event.getTickets() != null ? event.getTickets().size() : 0,
                partition, offset, key);

        try {
            // Step 1: Validate event
            validateEvent(event);

            // Step 2: Initialize tracking
            initializeTracking(event);

            // Step 3: Check if batch was cancelled
            if (isBatchCancelled(event.getBatchId())) {
                log.warn("⚠️ BATCH CANCELLED - Skipping chunk: batch={}, chunk={}",
                        event.getBatchId(), event.getChunkNumber());
                acknowledgment.acknowledge();
                return;
            }

            // Step 4: Process each ticket
            processTickets(event, context);

            // Step 5: Complete chunk
            completeChunk(event, context);

            // Step 6: Acknowledge
            acknowledgment.acknowledge();

            log.info("✅ BULK CHUNK PROCESSED - batch: {}, chunk: {}/{}, " +
                     "success: {}, failed: {}, skipped: {}, duration: {}ms",
                    event.getBatchId(),
                    event.getChunkNumber() + 1,
                    event.getTotalChunks(),
                    context.getSuccessCount(),
                    context.getFailureCount(),
                    context.getSkippedCount(),
                    context.getDurationMs());

        } catch (KafkaBulkProcessingException e) {
            handleBulkProcessingException(e, event, partition, offset, acknowledgment);

        } catch (Exception e) {
            handleUnexpectedException(e, event, partition, offset, acknowledgment);

        } finally {
            processingContext.remove();
        }
    }

    /**
     * Validates the bulk upload event.
     */
    private void validateEvent(BulkTicketUploadEvent event) {
        if (event == null) {
            throw new KafkaBulkProcessingException(
                    "Received null event",
                    "UNKNOWN", -1,
                    BulkProcessingErrorCode.NULL_REQUEST,
                    false, 0
            );
        }

        if (event.getBatchId() == null || event.getBatchId().isBlank()) {
            throw new KafkaBulkProcessingException(
                    "Event missing batch ID",
                    "UNKNOWN", event.getChunkNumber(),
                    BulkProcessingErrorCode.INVALID_ROW_DATA,
                    false, 0
            );
        }

        if (event.getTickets() == null) {
            throw new KafkaBulkProcessingException(
                    "Event has null tickets list",
                    event.getBatchId(), event.getChunkNumber(),
                    BulkProcessingErrorCode.NULL_REQUEST,
                    false, 0
            );
        }

        if (event.getTickets().isEmpty()) {
            log.warn("⚠️ Empty chunk received - batch: {}, chunk: {}",
                    event.getBatchId(), event.getChunkNumber());
        }
    }

    /**
     * Initializes tracking for the batch.
     */
    private void initializeTracking(BulkTicketUploadEvent event) {
        try {
            trackingService.initializeBatch(
                    event.getBatchId(),
                    event.getTotalChunks(),
                    event.getTickets().size() * event.getTotalChunks()
            );
        } catch (Exception e) {
            log.warn("⚠️ TRACKING INIT FAILED - batch: {}, error: {}. Continuing processing.",
                    event.getBatchId(), e.getMessage());
        }
    }

    /**
     * Checks if a batch has been cancelled.
     */
    private boolean isBatchCancelled(String batchId) {
        try {
            return trackingService.getBatchStatus(batchId)
                    .map(status -> "CANCELLED".equals(status.getStatus()))
                    .orElse(false);
        } catch (Exception e) {
            log.debug("Unable to check batch cancellation status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Processes all tickets in the chunk with individual error handling.
     */
    private void processTickets(BulkTicketUploadEvent event, ChunkProcessingContext context) {
        for (int i = 0; i < event.getTickets().size(); i++) {
            TicketCreateRequest request = event.getTickets().get(i);
            String ticketNumber = request != null ? request.getTicketNumber() : "UNKNOWN-" + i;

            try {
                processTicket(request, event.getBatchId(), ticketNumber);
                context.incrementSuccess();
                trackingService.recordSuccess(event.getBatchId(), ticketNumber);

            } catch (DuplicateTicketException e) {
                handleDuplicateTicket(event.getBatchId(), ticketNumber, e, context);

            } catch (NullRequestException e) {
                handleValidationError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.NULL_REQUEST, e.getMessage(), context);

            } catch (IllegalArgumentException e) {
                handleValidationError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.INVALID_ROW_DATA, e.getMessage(), context);

            } catch (InvalidTicketOperationException e) {
                handleValidationError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.INVALID_STATUS_TRANSITION, e.getMessage(), context);

            } catch (DataIntegrityViolationException e) {
                handleDatabaseError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.DUPLICATE_TICKET,
                        "Data integrity violation: " + e.getMessage(), context, false);

            } catch (DataAccessException e) {
                handleDatabaseError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.DATABASE_ERROR,
                        "Database error: " + e.getMessage(), context, true);

            } catch (TransactionException e) {
                handleDatabaseError(event.getBatchId(), ticketNumber,
                        BulkProcessingErrorCode.DATABASE_ERROR,
                        "Transaction error: " + e.getMessage(), context, true);

            } catch (RuntimeException e) {
                handleUnexpectedTicketError(event.getBatchId(), ticketNumber, e, context);
            }
        }
    }

    /**
     * Processes a single ticket.
     */
    private void processTicket(TicketCreateRequest request, String batchId, String ticketNumber) {
        if (request == null) {
            throw new NullRequestException("Ticket", "Ticket request is null");
        }

        log.debug("🔄 Processing ticket: {} in batch: {}", ticketNumber, batchId);

        TicketDTO createdTicket = ticketService.createTicket(request);

        log.debug("✅ Ticket created: id={}, ticketNumber={}",
                createdTicket.getId(), createdTicket.getTicketNumber());
    }

    /**
     * Handles duplicate ticket exceptions.
     */
    private void handleDuplicateTicket(String batchId, String ticketNumber,
                                        DuplicateTicketException e,
                                        ChunkProcessingContext context) {
        log.debug("⚠️ DUPLICATE SKIPPED - batch: {}, ticket: {}", batchId, ticketNumber);

        trackingService.recordFailure(batchId, ticketNumber,
                BulkProcessingErrorCode.DUPLICATE_TICKET.getCode(),
                "Duplicate ticket number");

        context.incrementSkipped();
    }

    /**
     * Handles validation errors.
     */
    private void handleValidationError(String batchId, String ticketNumber,
                                        BulkProcessingErrorCode errorCode,
                                        String message,
                                        ChunkProcessingContext context) {
        log.warn("❌ VALIDATION FAILED - batch: {}, ticket: {}, error: {}",
                batchId, ticketNumber, message);

        trackingService.recordFailure(batchId, ticketNumber,
                errorCode.getCode(), message);

        context.incrementFailure();
    }

    /**
     * Handles database errors.
     */
    private void handleDatabaseError(String batchId, String ticketNumber,
                                      BulkProcessingErrorCode errorCode,
                                      String message,
                                      ChunkProcessingContext context,
                                      boolean shouldLog) {
        if (shouldLog) {
            log.error("💥 DATABASE ERROR - batch: {}, ticket: {}, error: {}",
                    batchId, ticketNumber, message);
        } else {
            log.warn("⚠️ DATABASE CONSTRAINT - batch: {}, ticket: {}, error: {}",
                    batchId, ticketNumber, message);
        }

        trackingService.recordFailure(batchId, ticketNumber,
                errorCode.getCode(), message);

        context.incrementFailure();
    }

    /**
     * Handles unexpected errors for individual tickets.
     */
    private void handleUnexpectedTicketError(String batchId, String ticketNumber,
                                              RuntimeException e,
                                              ChunkProcessingContext context) {
        BulkProcessingErrorCode errorCode = BulkProcessingErrorCode.fromException(e);

        log.error("❌ UNEXPECTED ERROR - batch: {}, ticket: {}, errorCode: {}, error: {}",
                batchId, ticketNumber, errorCode.name(), e.getMessage(), e);

        trackingService.recordFailure(batchId, ticketNumber,
                errorCode.getCode(), e.getMessage());

        context.incrementFailure();
    }

    /**
     * Completes chunk processing and updates tracking.
     */
    private void completeChunk(BulkTicketUploadEvent event, ChunkProcessingContext context) {
        try {
            trackingService.completeChunk(event.getBatchId(), event.getChunkNumber());
        } catch (Exception e) {
            log.error("⚠️ CHUNK COMPLETION TRACKING FAILED - batch: {}, chunk: {}, error: {}",
                    event.getBatchId(), event.getChunkNumber(), e.getMessage());
        }
    }

    /**
     * Handles KafkaBulkProcessingException at chunk level.
     */
    private void handleBulkProcessingException(KafkaBulkProcessingException e,
                                                BulkTicketUploadEvent event,
                                                int partition, long offset,
                                                Acknowledgment acknowledgment) {
        log.error("❌ BULK PROCESSING ERROR - {}", e.getDetailedMessage());

        if (e.isRetryable()) {
            // Re-throw to trigger Kafka retry and eventually DLT
            log.warn("🔄 RETRYING - Error is retryable, will be sent to error handler");
            throw e;
        } else {
            // Non-retryable: acknowledge and move on
            log.warn("⏭️ SKIPPING - Error is not retryable, acknowledging message");
            recordChunkFailure(event, e);
            acknowledgment.acknowledge();
        }
    }

    /**
     * Handles unexpected exceptions at chunk level.
     */
    private void handleUnexpectedException(Exception e,
                                            BulkTicketUploadEvent event,
                                            int partition, long offset,
                                            Acknowledgment acknowledgment) {
        String batchId = event != null ? event.getBatchId() : "UNKNOWN";
        int chunkNumber = event != null ? event.getChunkNumber() : -1;

        log.error("💥 UNEXPECTED ERROR - batch: {}, chunk: {}, partition: {}, offset: {}, error: {}",
                batchId, chunkNumber, partition, offset, e.getMessage(), e);

        BulkProcessingErrorCode errorCode = BulkProcessingErrorCode.fromException(e);

        if (errorCode.isRetryable()) {
            // Re-throw for retry
            throw new KafkaBulkProcessingException(
                    "Unexpected error during chunk processing: " + e.getMessage(),
                    batchId, chunkNumber, errorCode, true,
                    event != null && event.getTickets() != null ? event.getTickets().size() : 0,
                    e
            );
        } else {
            // Acknowledge and log
            recordChunkFailure(event, e);
            acknowledgment.acknowledge();
        }
    }

    /**
     * Records a chunk-level failure.
     */
    private void recordChunkFailure(BulkTicketUploadEvent event, Exception e) {
        if (event == null) return;

        try {
            // Record all tickets in chunk as failed
            for (TicketCreateRequest ticket : event.getTickets()) {
                String ticketNumber = ticket != null ? ticket.getTicketNumber() : "UNKNOWN";
                trackingService.recordFailure(
                        event.getBatchId(),
                        ticketNumber,
                        BulkProcessingErrorCode.CHUNK_PROCESSING_FAILED.getCode(),
                        "Chunk processing failed: " + e.getMessage()
                );
            }

            // Mark chunk as completed (with all failures)
            trackingService.completeChunk(event.getBatchId(), event.getChunkNumber());

        } catch (Exception trackingError) {
            log.error("⚠️ FAILURE TRACKING ERROR - batch: {}, error: {}",
                    event.getBatchId(), trackingError.getMessage());
        }
    }

    /**
     * Consumes messages from the Dead Letter Topic.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.bulk-requests:ticket.bulk.requests}.DLT",
            groupId = "${app.kafka.consumer.bulk-group-id:ticket-bulk-consumers}-dlt"
    )
    public void consumeBulkDltEvent(
            ConsumerRecord<String, Object> record,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.error("☠️ DLT RECEIVED - Bulk processing message - topic: {}, partition: {}, " +
                  "offset: {}, key: {}", record.topic(), partition, offset, record.key());

        try {
            // Store for later reprocessing/analysis
            trackingService.recordDltMessage(
                    record.topic(),
                    record.key(),
                    record.value()
            );

            // Extract batch info if possible
            if (record.value() instanceof BulkTicketUploadEvent event) {
                log.error("☠️ DLT DETAILS - batch: {}, chunk: {}/{}, tickets: {}",
                        event.getBatchId(),
                        event.getChunkNumber() + 1,
                        event.getTotalChunks(),
                        event.getTickets() != null ? event.getTickets().size() : 0);
            }

        } catch (Exception e) {
            log.error("☠️ DLT PROCESSING ERROR - Failed to record DLT message: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Context for tracking chunk processing metrics.
     */
    private static class ChunkProcessingContext {
        private String batchId;
        private int chunkNumber;
        private LocalDateTime startTime;
        private int successCount;
        private int failureCount;
        private int skippedCount;

        public void reset() {
            this.batchId = null;
            this.chunkNumber = 0;
            this.startTime = null;
            this.successCount = 0;
            this.failureCount = 0;
            this.skippedCount = 0;
        }

        public void setBatchId(String batchId) { this.batchId = batchId; }
        public void setChunkNumber(int chunkNumber) { this.chunkNumber = chunkNumber; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

        public void incrementSuccess() { this.successCount++; }
        public void incrementFailure() { this.failureCount++; }
        public void incrementSkipped() { this.skippedCount++; }

        public String getBatchId() { return batchId; }
        public int getChunkNumber() { return chunkNumber; }
        public int getSuccessCount() { return successCount; }
        public int getFailureCount() { return failureCount; }
        public int getSkippedCount() { return skippedCount; }

        public long getDurationMs() {
            if (startTime == null) return 0;
            return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
        }
    }
}

