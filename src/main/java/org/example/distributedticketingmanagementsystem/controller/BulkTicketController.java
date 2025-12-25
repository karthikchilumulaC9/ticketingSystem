package org.example.distributedticketingmanagementsystem.controller;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.kafka.dto.BulkUploadResponse;
import org.example.distributedticketingmanagementsystem.kafka.dto.DltMessage;
import org.example.distributedticketingmanagementsystem.kafka.exception.*;
import org.example.distributedticketingmanagementsystem.kafka.service.BulkUploadProcessingService;
import org.example.distributedticketingmanagementsystem.kafka.service.BulkUploadTrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

/**
 * REST Controller for Kafka-based bulk ticket operations.
 * Provides comprehensive error handling and detailed API responses.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/v1/tickets/bulk/upload - Upload CSV for async Kafka processing</li>
 *   <li>GET /api/v1/tickets/bulk/status/{batchId} - Get batch status</li>
 *   <li>GET /api/v1/tickets/bulk/failures/{batchId} - Get batch failures</li>
 *   <li>GET /api/v1/tickets/bulk/active - Get all active batches</li>
 *   <li>POST /api/v1/tickets/bulk/cancel/{batchId} - Cancel a batch</li>
 *   <li>GET /api/v1/tickets/bulk/dlt - Get Dead Letter Topic messages</li>
 *   <li>POST /api/v1/tickets/bulk/dlt/reprocess/{id} - Reprocess DLT message</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@RestController
@RequestMapping("/api/tickets/bulk")
@RequiredArgsConstructor
public class BulkTicketController {

    private final BulkUploadProcessingService processingService;
    private final BulkUploadTrackingService trackingService;

    // ==================== Upload Endpoint ====================

    /**
     * Upload a CSV file for bulk ticket creation via Kafka.
     *
     * @param file       The CSV file to upload
     * @param uploadedBy User identifier (optional)
     * @return Response with batch ID and status
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> uploadBulkTickets(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "uploadedBy", required = false, defaultValue = "system")
            String uploadedBy) {

        log.info("📤 BULK UPLOAD REQUEST - file: {}, size: {} bytes, uploadedBy: {}",
                file.getOriginalFilename(), file.getSize(), uploadedBy);

        try {
            BulkUploadResponse response = processingService.processBulkUpload(file, uploadedBy);

            log.info("📤 BULK UPLOAD ACCEPTED - batchId: {}, tickets: {}",
                    response.getBatchId(), response.getTotalRecords());

            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(ApiResponseWrapper.success(
                            response,
                            "Bulk upload accepted for processing",
                            HttpStatus.ACCEPTED
                    ));

        } catch (CsvValidationException e) {
            return handleCsvValidationException(e);

        } catch (KafkaBulkProcessingException e) {
            return handleKafkaBulkProcessingException(e);

        } catch (KafkaProducerException e) {
            return handleKafkaProducerException(e);

        } catch (MaxUploadSizeExceededException e) {
            return handleMaxUploadSizeException(e);

        } catch (Exception e) {
            return handleUnexpectedException(e);
        }
    }

    // ==================== Status Endpoints ====================

    /**
     * Get the status of a bulk upload batch.
     */
    @GetMapping("/status/{batchId}")
    public ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> getBatchStatus(
            @PathVariable String batchId) {

        log.debug("📊 STATUS REQUEST - batchId: {}", batchId);

        try {
            Optional<BulkUploadResponse> statusOpt = processingService.getBatchStatus(batchId);

            if (statusOpt.isEmpty()) {
                log.warn("📊 BATCH NOT FOUND - batchId: {}", batchId);
                return ResponseEntity
                        .status(HttpStatus.NOT_FOUND)
                        .body(ApiResponseWrapper.error(
                                "Batch not found: " + batchId,
                                BulkProcessingErrorCode.UNKNOWN_ERROR.getCode(),
                                HttpStatus.NOT_FOUND
                        ));
            }

            return ResponseEntity.ok(ApiResponseWrapper.success(
                    statusOpt.get(),
                    "Batch status retrieved successfully",
                    HttpStatus.OK
            ));

        } catch (KafkaBulkProcessingException e) {
            log.error("📊 STATUS ERROR - batchId: {}, error: {}", batchId, e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponseWrapper.error(
                            e.getMessage(),
                            e.getErrorCode().getCode(),
                            HttpStatus.INTERNAL_SERVER_ERROR
                    ));

        } catch (Exception e) {
            log.error("📊 STATUS ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            return handleUnexpectedException(e);
        }
    }

    /**
     * Get failures for a batch.
     */
    @GetMapping("/failures/{batchId}")
    public ResponseEntity<ApiResponseWrapper<BatchFailuresResponse>> getBatchFailures(
            @PathVariable String batchId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        log.debug("📊 FAILURES REQUEST - batchId: {}, page: {}, size: {}", batchId, page, size);

        try {
            List<Map<String, String>> failures = trackingService.getBatchFailures(batchId);

            // Paginate
            int start = Math.min(page * size, failures.size());
            int end = Math.min(start + size, failures.size());
            List<Map<String, String>> pagedFailures = failures.subList(start, end);

            BatchFailuresResponse response = BatchFailuresResponse.builder()
                    .batchId(batchId)
                    .totalFailures(failures.size())
                    .page(page)
                    .pageSize(size)
                    .failures(pagedFailures)
                    .build();

            return ResponseEntity.ok(ApiResponseWrapper.success(
                    response,
                    "Failures retrieved successfully",
                    HttpStatus.OK
            ));

        } catch (Exception e) {
            log.error("📊 FAILURES ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            return handleUnexpectedException(e);
        }
    }

    /**
     * Get all active batches.
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponseWrapper<ActiveBatchesResponse>> getActiveBatches() {
        log.debug("📊 ACTIVE BATCHES REQUEST");

        try {
            Set<String> activeBatches = trackingService.getActiveBatches();

            List<BulkUploadResponse> batchDetails = new ArrayList<>();
            for (String batchId : activeBatches) {
                processingService.getBatchStatus(batchId)
                        .ifPresent(batchDetails::add);
            }

            ActiveBatchesResponse response = ActiveBatchesResponse.builder()
                    .count(activeBatches.size())
                    .batches(batchDetails)
                    .timestamp(LocalDateTime.now())
                    .build();

            return ResponseEntity.ok(ApiResponseWrapper.success(
                    response,
                    "Active batches retrieved successfully",
                    HttpStatus.OK
            ));

        } catch (Exception e) {
            log.error("📊 ACTIVE BATCHES ERROR - error: {}", e.getMessage(), e);
            return handleUnexpectedException(e);
        }
    }

    // ==================== Cancel Endpoint ====================

    /**
     * Cancel a bulk upload batch.
     */
    @PostMapping("/cancel/{batchId}")
    public ResponseEntity<ApiResponseWrapper<CancelResponse>> cancelBatch(
            @PathVariable String batchId,
            @RequestParam(value = "reason", required = false) String reason) {

        log.info("📤 CANCEL REQUEST - batchId: {}, reason: {}", batchId, reason);

        try {
            boolean cancelled = processingService.cancelBatch(batchId);

            CancelResponse response = CancelResponse.builder()
                    .batchId(batchId)
                    .cancelled(cancelled)
                    .reason(reason)
                    .cancelledAt(LocalDateTime.now())
                    .message(cancelled ?
                            "Batch marked for cancellation" :
                            "Unable to cancel batch - may already be complete")
                    .build();

            return ResponseEntity.ok(ApiResponseWrapper.success(
                    response,
                    cancelled ? "Batch cancelled" : "Cancel request processed",
                    HttpStatus.OK
            ));

        } catch (Exception e) {
            log.error("📤 CANCEL ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            return handleUnexpectedException(e);
        }
    }

    // ==================== DLT Endpoints ====================

    /**
     * Get Dead Letter Topic messages.
     */
    @GetMapping("/dlt")
    public ResponseEntity<ApiResponseWrapper<DltMessagesResponse>> getDltMessages(
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        log.debug("☠️ DLT REQUEST - topic: {}, limit: {}", topic, limit);

        try {
            String dltTopic = topic != null ? topic : "ticket.bulk.requests.DLT";
            List<DltMessage> messages = trackingService.getDltMessages(dltTopic);

            // Limit results
            List<DltMessage> limitedMessages = messages.size() > limit ?
                    messages.subList(0, limit) : messages;

            DltMessagesResponse response = DltMessagesResponse.builder()
                    .topic(dltTopic)
                    .totalMessages(messages.size())
                    .returnedMessages(limitedMessages.size())
                    .messages(limitedMessages)
                    .retrievedAt(LocalDateTime.now())
                    .build();

            return ResponseEntity.ok(ApiResponseWrapper.success(
                    response,
                    "DLT messages retrieved successfully",
                    HttpStatus.OK
            ));

        } catch (Exception e) {
            log.error("☠️ DLT ERROR - error: {}", e.getMessage(), e);
            return handleUnexpectedException(e);
        }
    }

    /**
     * Reprocess a DLT message.
     */
    @PostMapping("/dlt/reprocess/{messageId}")
    public ResponseEntity<ApiResponseWrapper<ReprocessResponse>> reprocessDltMessage(
            @PathVariable String messageId) {

        log.info("🔄 DLT REPROCESS REQUEST - messageId: {}", messageId);

        // TODO: Implement reprocessing logic
        ReprocessResponse response = ReprocessResponse.builder()
                .messageId(messageId)
                .status("NOT_IMPLEMENTED")
                .message("DLT reprocessing is not yet fully implemented")
                .timestamp(LocalDateTime.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponseWrapper.success(
                        response,
                        "Feature not implemented",
                        HttpStatus.NOT_IMPLEMENTED
                ));
    }

    // ==================== Exception Handlers ====================

    /**
     * Handles CSV validation exceptions.
     */
    private ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> handleCsvValidationException(
            CsvValidationException e) {

        log.error("❌ CSV VALIDATION ERROR - file: {}, errors: {}",
                e.getFilename(), e.getInvalidRowCount());

        BulkUploadResponse errorResponse = BulkUploadResponse.error(
                null,
                BulkProcessingErrorCode.INVALID_FILE_FORMAT,
                e.getErrorSummary()
        );

        // Add validation errors to response
        for (var error : e.getErrors()) {
            errorResponse.getFailedRecords().add(new BulkUploadResponse.FailedRecord(
                    "Line " + error.lineNumber(),
                    BulkProcessingErrorCode.INVALID_ROW_DATA.getCode(),
                    error.message(),
                    LocalDateTime.now()
            ));
        }

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.<BulkUploadResponse>builder()
                        .success(false)
                        .message("CSV validation failed")
                        .errorCode(BulkProcessingErrorCode.INVALID_FILE_FORMAT.getCode())
                        .errorDetails(e.getErrorSummary())
                        .validationErrors(e.getErrors().stream()
                                .map(err -> Map.of(
                                        "line", String.valueOf(err.lineNumber()),
                                        "field", err.field(),
                                        "message", err.message()
                                ))
                                .toList())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .data(errorResponse)
                        .build());
    }

    /**
     * Handles Kafka bulk processing exceptions.
     */
    private ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> handleKafkaBulkProcessingException(
            KafkaBulkProcessingException e) {

        log.error("❌ BULK PROCESSING ERROR - {}", e.getDetailedMessage());

        HttpStatus status = e.isRetryable() ?
                HttpStatus.SERVICE_UNAVAILABLE :
                HttpStatus.BAD_REQUEST;

        BulkUploadResponse errorResponse = BulkUploadResponse.error(
                e.getBatchId(),
                e.getErrorCode(),
                e.getMessage()
        );

        return ResponseEntity
                .status(status)
                .body(ApiResponseWrapper.<BulkUploadResponse>builder()
                        .success(false)
                        .message("Bulk processing error")
                        .errorCode(e.getErrorCode().getCode())
                        .errorDetails(e.getDetailedMessage())
                        .retryable(e.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(status.value())
                        .data(errorResponse)
                        .build());
    }

    /**
     * Handles Kafka producer exceptions.
     */
    private ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> handleKafkaProducerException(
            KafkaProducerException e) {

        log.error("❌ KAFKA PRODUCER ERROR - {}", e.getDetailedDescription());

        BulkUploadResponse errorResponse = BulkUploadResponse.error(
                null,
                BulkProcessingErrorCode.KAFKA_PRODUCER_ERROR,
                e.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseWrapper.<BulkUploadResponse>builder()
                        .success(false)
                        .message("Kafka producer error")
                        .errorCode(BulkProcessingErrorCode.KAFKA_PRODUCER_ERROR.getCode())
                        .errorDetails(e.getDetailedDescription())
                        .retryable(e.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .data(errorResponse)
                        .build());
    }

    /**
     * Handles file size exceeded exceptions.
     */
    private ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> handleMaxUploadSizeException(
            MaxUploadSizeExceededException e) {

        log.error("❌ FILE TOO LARGE - max size exceeded: {}", e.getMessage());

        BulkUploadResponse errorResponse = BulkUploadResponse.error(
                null,
                BulkProcessingErrorCode.BATCH_SIZE_EXCEEDED,
                "File size exceeds maximum allowed limit"
        );

        return ResponseEntity
                .status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiResponseWrapper.<BulkUploadResponse>builder()
                        .success(false)
                        .message("File too large")
                        .errorCode(BulkProcessingErrorCode.BATCH_SIZE_EXCEEDED.getCode())
                        .errorDetails("Maximum file size exceeded. Please reduce file size and try again.")
                        .retryable(false)
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.CONTENT_TOO_LARGE.value())
                        .data(errorResponse)
                        .build());
    }

    /**
     * Handles unexpected exceptions.
     */
    private <T> ResponseEntity<ApiResponseWrapper<T>> handleUnexpectedException(Exception e) {
        log.error("💥 UNEXPECTED ERROR - {}", e.getMessage(), e);

        BulkProcessingErrorCode errorCode = BulkProcessingErrorCode.fromException(e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.<T>builder()
                        .success(false)
                        .message("An unexpected error occurred")
                        .errorCode(errorCode.getCode())
                        .errorDetails(e.getMessage())
                        .retryable(errorCode.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .build());
    }

    // ==================== Response DTOs ====================

    /**
     * Generic API response wrapper.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponseWrapper<T> {
        private boolean success;
        private String message;
        private String errorCode;
        private String errorDetails;
        private Boolean retryable;
        private List<Map<String, String>> validationErrors;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;

        private int status;
        private T data;

        public static <T> ApiResponseWrapper<T> success(T data, String message, HttpStatus status) {
            return ApiResponseWrapper.<T>builder()
                    .success(true)
                    .message(message)
                    .timestamp(LocalDateTime.now())
                    .status(status.value())
                    .data(data)
                    .build();
        }

        public static <T> ApiResponseWrapper<T> error(String message, String errorCode, HttpStatus status) {
            return ApiResponseWrapper.<T>builder()
                    .success(false)
                    .message(message)
                    .errorCode(errorCode)
                    .timestamp(LocalDateTime.now())
                    .status(status.value())
                    .build();
        }
    }

    /**
     * Response for batch failures.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchFailuresResponse {
        private String batchId;
        private int totalFailures;
        private int page;
        private int pageSize;
        private List<Map<String, String>> failures;
    }

    /**
     * Response for active batches.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActiveBatchesResponse {
        private int count;
        private List<BulkUploadResponse> batches;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;
    }

    /**
     * Response for cancel operation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelResponse {
        private String batchId;
        private boolean cancelled;
        private String reason;
        private String message;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime cancelledAt;
    }

    /**
     * Response for DLT messages.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DltMessagesResponse {
        private String topic;
        private int totalMessages;
        private int returnedMessages;
        private List<DltMessage> messages;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime retrievedAt;
    }

    /**
     * Response for reprocess operation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReprocessResponse {
        private String messageId;
        private String status;
        private String message;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;
    }
}

