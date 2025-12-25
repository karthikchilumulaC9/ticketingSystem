package org.example.distributedticketingmanagementsystem.kafka.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.KafkaException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for Kafka bulk processing operations.
 * Provides consistent error responses across all bulk processing endpoints.
 *
 * <p>Handles:
 * <ul>
 *   <li>KafkaBulkProcessingException - Bulk processing errors</li>
 *   <li>KafkaProducerException - Producer errors</li>
 *   <li>KafkaConsumerException - Consumer errors</li>
 *   <li>CsvValidationException - CSV validation errors</li>
 *   <li>DeadLetterQueueException - DLT errors</li>
 *   <li>MaxUploadSizeExceededException - File size errors</li>
 *   <li>KafkaException - General Kafka errors</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.example.distributedticketingmanagementsystem.controller")
public class KafkaExceptionHandler {

    /**
     * Handles KafkaBulkProcessingException.
     */
    @ExceptionHandler(KafkaBulkProcessingException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleKafkaBulkProcessingException(
            KafkaBulkProcessingException e) {

        log.error("❌ BULK PROCESSING EXCEPTION: {}", e.getDetailedMessage());

        HttpStatus status = determineHttpStatus(e);

        return ResponseEntity
                .status(status)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("Bulk processing error")
                        .errorCode(e.getErrorCode().getCode())
                        .errorDetails(e.getDetailedMessage())
                        .retryable(e.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(status.value())
                        .data(Map.of(
                                "batchId", e.getBatchId() != null ? e.getBatchId() : "N/A",
                                "chunkNumber", e.getChunkNumber(),
                                "affectedRecords", e.getAffectedRecords(),
                                "errorCode", e.getErrorCode().name(),
                                "errorDescription", e.getErrorCode().getDescription()
                        ))
                        .build());
    }

    /**
     * Handles KafkaProducerException.
     */
    @ExceptionHandler(KafkaProducerException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleKafkaProducerException(
            KafkaProducerException e) {

        log.error("❌ KAFKA PRODUCER EXCEPTION: {}", e.getDetailedDescription());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("Kafka producer error")
                        .errorCode(e.getErrorCode().getCode())
                        .errorDetails(e.getDetailedDescription())
                        .retryable(e.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .data(Map.of(
                                "topic", e.getTopic() != null ? e.getTopic() : "N/A",
                                "messageKey", e.getMessageKey() != null ? e.getMessageKey() : "N/A",
                                "correlationId", e.getCorrelationId() != null ? e.getCorrelationId() : "N/A",
                                "failureTimestamp", e.getFailureTimestamp().toString()
                        ))
                        .build());
    }

    /**
     * Handles KafkaConsumerException.
     */
    @ExceptionHandler(KafkaConsumerException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleKafkaConsumerException(
            KafkaConsumerException e) {

        log.error("❌ KAFKA CONSUMER EXCEPTION: {}", e.getDetailedDescription());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("Kafka consumer error")
                        .errorCode(e.getErrorCode().getCode())
                        .errorDetails(e.getDetailedDescription())
                        .retryable(e.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .data(Map.of(
                                "topic", e.getTopic() != null ? e.getTopic() : "N/A",
                                "partition", e.getPartition(),
                                "offset", e.getOffset(),
                                "consumerGroup", e.getConsumerGroup() != null ? e.getConsumerGroup() : "N/A",
                                "attemptNumber", e.getAttemptNumber(),
                                "shouldSendToDlt", e.shouldSendToDlt()
                        ))
                        .build());
    }

    /**
     * Handles CsvValidationException.
     */
    @ExceptionHandler(CsvValidationException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleCsvValidationException(
            CsvValidationException e) {

        log.error("❌ CSV VALIDATION EXCEPTION: {}", e.getErrorSummary());

        List<Map<String, String>> validationErrors = e.getErrors().stream()
                .map(error -> Map.of(
                        "line", String.valueOf(error.lineNumber()),
                        "field", error.field(),
                        "message", error.message(),
                        "value", error.actualValue() != null ? error.actualValue() : ""
                ))
                .collect(Collectors.toList());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("CSV validation failed")
                        .errorCode(BulkProcessingErrorCode.INVALID_FILE_FORMAT.getCode())
                        .errorDetails(e.getErrorSummary())
                        .retryable(false)
                        .validationErrors(validationErrors)
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .data(Map.of(
                                "filename", e.getFilename() != null ? e.getFilename() : "N/A",
                                "totalRows", e.getTotalRows(),
                                "validRows", e.getValidRows(),
                                "invalidRows", e.getInvalidRowCount()
                        ))
                        .build());
    }

    /**
     * Handles DeadLetterQueueException.
     */
    @ExceptionHandler(DeadLetterQueueException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleDeadLetterQueueException(
            DeadLetterQueueException e) {

        log.error("☠️ DLT EXCEPTION: {}", e.getDetailedDescription());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("Message sent to Dead Letter Topic")
                        .errorCode(e.getErrorCode().getCode())
                        .errorDetails(e.getDetailedDescription())
                        .retryable(false)
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .data(Map.of(
                                "originalTopic", e.getOriginalTopic() != null ? e.getOriginalTopic() : "N/A",
                                "messageKey", e.getMessageKey() != null ? e.getMessageKey() : "N/A",
                                "partition", e.getPartition(),
                                "offset", e.getOffset(),
                                "retryAttempts", e.getRetryAttempts(),
                                "dltTimestamp", e.getDltTimestamp().toString()
                        ))
                        .build());
    }

    /**
     * Handles MaxUploadSizeExceededException.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e) {

        log.error("❌ FILE SIZE EXCEEDED: {}", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("File size exceeds maximum limit")
                        .errorCode(BulkProcessingErrorCode.BATCH_SIZE_EXCEEDED.getCode())
                        .errorDetails("Maximum file size exceeded. Please reduce file size and try again.")
                        .retryable(false)
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.CONTENT_TOO_LARGE.value())
                        .data(Map.of(
                                "maxSize", e.getMaxUploadSize(),
                                "message", "Please ensure your file is under the maximum size limit"
                        ))
                        .build());
    }

    /**
     * Handles general KafkaException.
     */
    @ExceptionHandler(KafkaException.class)
    public ResponseEntity<ApiResponseWrapper<Object>> handleKafkaException(KafkaException e) {

        log.error("❌ KAFKA EXCEPTION: {}", e.getMessage(), e);

        BulkProcessingErrorCode errorCode = BulkProcessingErrorCode.fromException(e);

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseWrapper.builder()
                        .success(false)
                        .message("Kafka service error")
                        .errorCode(errorCode.getCode())
                        .errorDetails(e.getMessage())
                        .retryable(errorCode.isRetryable())
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .data(Map.of(
                                "exceptionType", e.getClass().getSimpleName(),
                                "cause", e.getCause() != null ? e.getCause().getMessage() : "Unknown"
                        ))
                        .build());
    }

    /**
     * Determines the appropriate HTTP status based on the exception.
     */
    private HttpStatus determineHttpStatus(KafkaBulkProcessingException e) {
        BulkProcessingErrorCode errorCode = e.getErrorCode();

        // Validation errors -> Bad Request
        if (errorCode.getCode().startsWith("V")) {
            return HttpStatus.BAD_REQUEST;
        }

        // Kafka infrastructure errors -> Service Unavailable
        if (errorCode.getCode().startsWith("K")) {
            return e.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        }

        // Infrastructure errors -> Service Unavailable if retryable
        if (errorCode.getCode().startsWith("I")) {
            return e.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // Processing errors
        if (errorCode == BulkProcessingErrorCode.DUPLICATE_TICKET) {
            return HttpStatus.CONFLICT;
        }

        // Default
        return e.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    // ==================== Response DTO ====================

    /**
     * Generic API response wrapper for exception handling.
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
}

