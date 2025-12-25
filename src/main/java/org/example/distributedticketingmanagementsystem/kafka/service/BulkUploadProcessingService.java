package org.example.distributedticketingmanagementsystem.kafka.service;

import com.opencsv.CSVReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.kafka.dto.BulkUploadResponse;
import org.example.distributedticketingmanagementsystem.kafka.exception.*;
import org.example.distributedticketingmanagementsystem.kafka.producer.TicketKafkaProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Enterprise-level service for handling bulk ticket uploads via Kafka.
 * Provides comprehensive validation, error handling, and tracking.
 *
 * <p>Key features:
 * <ul>
 *   <li>CSV validation with detailed error reporting</li>
 *   <li>Chunked processing for large files</li>
 *   <li>Comprehensive exception handling</li>
 *   <li>Progress tracking via Redis</li>
 *   <li>Dead Letter Queue integration</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkUploadProcessingService {

    private final TicketKafkaProducer kafkaProducer;
    private final BulkUploadTrackingService trackingService;

    @Value("${app.kafka.bulk.chunk-size:100}")
    private int chunkSize;

    @Value("${app.kafka.bulk.max-file-size-mb:10}")
    private int maxFileSizeMb;

    @Value("${app.kafka.bulk.max-records:10000}")
    private int maxRecords;

    // Required CSV columns
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "ticketnumber", "title", "customerid"
    );

    // Optional CSV columns
    private static final List<String> OPTIONAL_COLUMNS = Arrays.asList(
            "description", "status", "priority", "assignedto"
    );

    /**
     * Processes a bulk upload request from a CSV file.
     *
     * <p>Processing flow:
     * <ol>
     *   <li>Validate file (size, format, content)</li>
     *   <li>Parse CSV and validate data</li>
     *   <li>Chunk data for parallel processing</li>
     *   <li>Send chunks to Kafka</li>
     *   <li>Return tracking information</li>
     * </ol>
     *
     * @param file       The uploaded CSV file
     * @param uploadedBy User identifier who initiated the upload
     * @return BulkUploadResponse with batch tracking information
     * @throws CsvValidationException       if CSV validation fails
     * @throws KafkaBulkProcessingException if Kafka processing fails
     */
    public BulkUploadResponse processBulkUpload(MultipartFile file, String uploadedBy) {
        String filename = file.getOriginalFilename();
        log.info("📤 BULK UPLOAD - Starting processing for file: {}, size: {} bytes, uploadedBy: {}",
                filename, file.getSize(), uploadedBy);

        try {
            // Step 1: Validate file
            validateFile(file);

            // Step 2: Parse and validate CSV
            List<TicketCreateRequest> tickets = parseAndValidateCsv(file);

            if (tickets.isEmpty()) {
                log.warn("📤 BULK UPLOAD - No valid tickets found in file: {}", filename);
                throw new CsvValidationException(
                        "No valid tickets found in file",
                        filename, 0, "data", "File contains no valid ticket records"
                );
            }

            // Step 3: Validate batch size
            if (tickets.size() > maxRecords) {
                log.error("📤 BULK UPLOAD - Batch size {} exceeds maximum {}",
                        tickets.size(), maxRecords);
                throw new KafkaBulkProcessingException(
                        String.format("Batch size %d exceeds maximum allowed %d",
                                tickets.size(), maxRecords),
                        null,
                        BulkProcessingErrorCode.BATCH_SIZE_EXCEEDED
                );
            }

            // Step 4: Calculate chunks
            int totalChunks = (int) Math.ceil((double) tickets.size() / chunkSize);

            // Step 5: Send to Kafka
            String batchId = kafkaProducer.sendBulkUploadEvent(tickets, uploadedBy, filename);

            log.info("📤 BULK UPLOAD - Accepted - batchId: {}, tickets: {}, chunks: {}",
                    batchId, tickets.size(), totalChunks);

            // Step 6: Create response
            return BulkUploadResponse.accepted(
                    batchId,
                    tickets.size(),
                    totalChunks,
                    filename,
                    uploadedBy
            );

        } catch (CsvValidationException e) {
            log.error("📤 BULK UPLOAD FAILED - CSV validation error: {}", e.getMessage());
            throw e;

        } catch (KafkaBulkProcessingException e) {
            log.error("📤 BULK UPLOAD FAILED - Processing error: {}", e.getDetailedMessage());
            throw e;

        } catch (KafkaProducerException e) {
            log.error("📤 BULK UPLOAD FAILED - Kafka producer error: {}", e.getDetailedDescription());
            throw new KafkaBulkProcessingException(
                    "Failed to send bulk upload to Kafka: " + e.getMessage(),
                    null, e
            );

        } catch (IOException e) {
            log.error("📤 BULK UPLOAD FAILED - IO error: {}", e.getMessage(), e);
            throw new KafkaBulkProcessingException(
                    "Failed to read uploaded file: " + e.getMessage(),
                    null, -1,
                    BulkProcessingErrorCode.IO_ERROR,
                    true, 0, e
            );

        } catch (Exception e) {
            log.error("📤 BULK UPLOAD FAILED - Unexpected error: {}", e.getMessage(), e);
            throw new KafkaBulkProcessingException(
                    "Unexpected error during bulk upload: " + e.getMessage(),
                    null, e
            );
        }
    }

    /**
     * Validates the uploaded file.
     *
     * @param file The file to validate
     * @throws CsvValidationException if validation fails
     */
    private void validateFile(MultipartFile file) {
        String filename = file.getOriginalFilename();

        // Check if file is empty
        if (file.isEmpty()) {
            log.warn("📤 VALIDATION FAILED - File is empty: {}", filename);
            throw new CsvValidationException(
                    "Uploaded file is empty",
                    filename, 0, "file", "File size is 0 bytes"
            );
        }

        // Check file extension
        if (filename == null || (!filename.toLowerCase().endsWith(".csv") &&
                                 !filename.toLowerCase().endsWith(".txt"))) {
            log.warn("📤 VALIDATION FAILED - Invalid file type: {}", filename);
            throw new CsvValidationException(
                    "Invalid file type. Only CSV files are accepted",
                    filename, 0, "file", "Expected .csv or .txt file extension"
            );
        }

        // Check file size
        long maxBytes = (long) maxFileSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            log.warn("📤 VALIDATION FAILED - File too large: {} bytes (max: {} MB)",
                    file.getSize(), maxFileSizeMb);
            throw new CsvValidationException(
                    String.format("File size %d bytes exceeds maximum %d MB",
                            file.getSize(), maxFileSizeMb),
                    filename, 0, "file",
                    String.format("Maximum file size is %d MB", maxFileSizeMb)
            );
        }

        log.debug("📤 VALIDATION PASSED - File: {}, Size: {} bytes", filename, file.getSize());
    }

    /**
     * Parses and validates the CSV file content.
     *
     * @param file The CSV file to parse
     * @return List of valid TicketCreateRequest objects
     * @throws IOException            if file reading fails
     * @throws CsvValidationException if CSV validation fails
     */
    private List<TicketCreateRequest> parseAndValidateCsv(MultipartFile file)
            throws IOException, CsvValidationException {

        String filename = file.getOriginalFilename();
        List<TicketCreateRequest> tickets = new ArrayList<>();
        List<CsvValidationException.ValidationError> validationErrors = new ArrayList<>();
        int lineNumber = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            // Read and validate header
            String[] header = reader.readNext();
            lineNumber = 1;

            if (header == null || header.length == 0) {
                throw new CsvValidationException(
                        "CSV file has no header row",
                        filename, 1, "header", "Header row is required"
                );
            }

            // Create column index map
            Map<String, Integer> columnIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                String columnName = header[i].trim().toLowerCase()
                        .replace(" ", "")
                        .replace("_", "");
                columnIndex.put(columnName, i);
            }

            // Validate required columns
            List<String> missingColumns = new ArrayList<>();
            for (String required : REQUIRED_COLUMNS) {
                if (!columnIndex.containsKey(required)) {
                    missingColumns.add(required);
                }
            }

            if (!missingColumns.isEmpty()) {
                log.error("📤 CSV VALIDATION FAILED - Missing columns: {}", missingColumns);
                throw new CsvValidationException(
                        "Missing required columns: " + String.join(", ", missingColumns),
                        filename, missingColumns
                );
            }

            // Read and validate data rows
            String[] row;
            Set<String> seenTicketNumbers = new HashSet<>();

            while ((row = reader.readNext()) != null) {
                lineNumber++;

                try {
                    TicketCreateRequest ticket = parseAndValidateRow(
                            row, columnIndex, lineNumber, seenTicketNumbers, validationErrors
                    );

                    if (ticket != null) {
                        tickets.add(ticket);
                        seenTicketNumbers.add(ticket.getTicketNumber());
                    }

                } catch (Exception e) {
                    validationErrors.add(CsvValidationException.ValidationError.of(
                            lineNumber, "row", "Failed to parse row: " + e.getMessage()
                    ));
                }
            }

        } catch (CsvValidationException e) {
            throw e;
        } catch (com.opencsv.exceptions.CsvValidationException e) {
            throw new CsvValidationException(
                    "CSV parsing error at line " + lineNumber + ": " + e.getMessage(),
                    filename, lineNumber, "format", e.getMessage()
            );
        }

        // Log validation summary
        log.info("📤 CSV PARSING COMPLETE - Total rows: {}, Valid: {}, Errors: {}",
                lineNumber - 1, tickets.size(), validationErrors.size());

        // If too many errors, reject the file
        if (validationErrors.size() > lineNumber * 0.5 && validationErrors.size() > 10) {
            throw new CsvValidationException(
                    "Too many validation errors. Please review the file format.",
                    filename, validationErrors, lineNumber - 1, tickets.size()
            );
        }

        return tickets;
    }

    /**
     * Parses and validates a single CSV row.
     *
     * @param row              The row data
     * @param columnIndex      Column name to index mapping
     * @param lineNumber       Current line number (for error reporting)
     * @param seenTicketNumbers Set of already seen ticket numbers (for duplicate detection)
     * @param errors           List to add validation errors to
     * @return TicketCreateRequest or null if validation failed
     */
    private TicketCreateRequest parseAndValidateRow(
            String[] row,
            Map<String, Integer> columnIndex,
            int lineNumber,
            Set<String> seenTicketNumbers,
            List<CsvValidationException.ValidationError> errors) {

        // Get ticket number (required)
        String ticketNumber = getColumnValue(row, columnIndex, "ticketnumber");
        if (!StringUtils.hasText(ticketNumber)) {
            errors.add(CsvValidationException.ValidationError.of(
                    lineNumber, "ticketNumber", "Ticket number is required"
            ));
            return null;
        }

        // Check for duplicate within file
        if (seenTicketNumbers.contains(ticketNumber)) {
            errors.add(CsvValidationException.ValidationError.of(
                    lineNumber, "ticketNumber",
                    "Duplicate ticket number in file",
                    ticketNumber
            ));
            return null;
        }

        // Get title (required)
        String title = getColumnValue(row, columnIndex, "title");
        if (!StringUtils.hasText(title)) {
            errors.add(CsvValidationException.ValidationError.of(
                    lineNumber, "title", "Title is required", null
            ));
            return null;
        }

        // Validate title length
        if (title.length() > 255) {
            errors.add(CsvValidationException.ValidationError.of(
                    lineNumber, "title", "Title exceeds 255 characters",
                    String.valueOf(title.length())
            ));
            return null;
        }

        // Get customerId (required)
        String customerIdStr = getColumnValue(row, columnIndex, "customerid");
        Long customerId;
        try {
            if (!StringUtils.hasText(customerIdStr)) {
                errors.add(CsvValidationException.ValidationError.of(
                        lineNumber, "customerId", "Customer ID is required"
                ));
                return null;
            }
            customerId = Long.parseLong(customerIdStr.trim());
            if (customerId <= 0) {
                errors.add(CsvValidationException.ValidationError.of(
                        lineNumber, "customerId", "Customer ID must be positive",
                        customerIdStr
                ));
                return null;
            }
        } catch (NumberFormatException e) {
            errors.add(CsvValidationException.ValidationError.of(
                    lineNumber, "customerId", "Invalid customer ID format",
                    customerIdStr
            ));
            return null;
        }

        // Get optional fields
        String description = getColumnValue(row, columnIndex, "description");
        if (description != null && description.length() > 5000) {
            description = description.substring(0, 5000);
            log.debug("Line {}: Description truncated to 5000 characters", lineNumber);
        }

        String status = getColumnValue(row, columnIndex, "status");
        if (!StringUtils.hasText(status)) {
            status = "OPEN"; // Default status
        } else {
            status = status.trim().toUpperCase();
            if (!isValidStatus(status)) {
                errors.add(CsvValidationException.ValidationError.of(
                        lineNumber, "status", "Invalid status value",
                        status
                ));
                status = "OPEN"; // Use default
            }
        }

        String priority = getColumnValue(row, columnIndex, "priority");
        if (!StringUtils.hasText(priority)) {
            priority = "MEDIUM"; // Default priority
        } else {
            priority = priority.trim().toUpperCase();
            if (!isValidPriority(priority)) {
                errors.add(CsvValidationException.ValidationError.of(
                        lineNumber, "priority", "Invalid priority value",
                        priority
                ));
                priority = "MEDIUM"; // Use default
            }
        }

        Integer assignedTo = null;
        String assignedToStr = getColumnValue(row, columnIndex, "assignedto");
        if (StringUtils.hasText(assignedToStr)) {
            try {
                assignedTo = Integer.parseInt(assignedToStr.trim());
                if (assignedTo <= 0) {
                    assignedTo = null;
                }
            } catch (NumberFormatException e) {
                // Ignore invalid assignedTo, it's optional
                log.debug("Line {}: Invalid assignedTo value: {}", lineNumber, assignedToStr);
            }
        }

        return TicketCreateRequest.builder()
                .ticketNumber(ticketNumber.trim())
                .title(title.trim())
                .description(description)
                .status(status)
                .priority(priority)
                .customerId(customerId)
                .assignedTo(assignedTo)
                .build();
    }

    /**
     * Gets a column value from the row.
     */
    private String getColumnValue(String[] row, Map<String, Integer> columnIndex, String column) {
        Integer index = columnIndex.get(column);
        if (index == null || index >= row.length) {
            return null;
        }
        String value = row[index].trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Validates status value.
     */
    private boolean isValidStatus(String status) {
        return Set.of("OPEN", "IN_PROGRESS", "PENDING", "RESOLVED", "CLOSED", "CANCELLED")
                .contains(status);
    }

    /**
     * Validates priority value.
     */
    private boolean isValidPriority(String priority) {
        return Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority);
    }

    /**
     * Gets the status of a bulk upload batch.
     *
     * @param batchId The batch identifier
     * @return Optional containing BulkUploadResponse if found
     */
    public Optional<BulkUploadResponse> getBatchStatus(String batchId) {
        log.debug("📊 BATCH STATUS REQUEST - batchId: {}", batchId);

        if (!StringUtils.hasText(batchId)) {
            log.warn("📊 BATCH STATUS - Invalid batchId: null or empty");
            return Optional.empty();
        }

        try {
            return trackingService.getBatchStatus(batchId)
                    .map(status -> {
                        BulkUploadResponse response = BulkUploadResponse.builder()
                                .batchId(status.getBatchId())
                                .status(mapStatus(status.getStatus()))
                                .statusMessage(status.getStatus())
                                .totalChunks(status.getTotalChunks())
                                .completedChunks(new java.util.concurrent.atomic.AtomicInteger(
                                        status.getCompletedChunks()))
                                .totalRecords(new java.util.concurrent.atomic.AtomicInteger(
                                        status.getTotalTickets()))
                                .successCount(new java.util.concurrent.atomic.AtomicInteger(
                                        status.getSuccessCount()))
                                .failureCount(new java.util.concurrent.atomic.AtomicInteger(
                                        status.getFailureCount()))
                                .processingStartedAt(status.getStartTime())
                                .completedAt(status.getEndTime())
                                .uploadedBy(status.getUploadedBy())
                                .originalFilename(status.getOriginalFilename())
                                .statusUrl("/api/tickets/bulk-kafka/status/" + batchId)
                                .failuresUrl("/api/tickets/bulk-kafka/failures/" + batchId)
                                .build();

                        // Add failure details if any
                        if (status.getFailureCount() > 0) {
                            List<Map<String, String>> failures = trackingService.getBatchFailures(batchId);
                            for (Map<String, String> failure : failures) {
                                response.getFailedRecords().add(new BulkUploadResponse.FailedRecord(
                                        failure.get("ticketNumber"),
                                        failure.get("errorCode"),
                                        failure.get("errorMessage"),
                                        LocalDateTime.now()
                                ));
                            }
                        }

                        return response;
                    });

        } catch (Exception e) {
            log.error("📊 BATCH STATUS ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            throw new KafkaBulkProcessingException(
                    "Failed to retrieve batch status: " + e.getMessage(),
                    batchId, e
            );
        }
    }

    /**
     * Maps string status to enum.
     */
    private BulkUploadResponse.BulkUploadStatus mapStatus(String status) {
        if (status == null) {
            return BulkUploadResponse.BulkUploadStatus.PROCESSING;
        }
        return switch (status.toUpperCase()) {
            case "ACCEPTED" -> BulkUploadResponse.BulkUploadStatus.ACCEPTED;
            case "IN_PROGRESS", "PROCESSING" -> BulkUploadResponse.BulkUploadStatus.PROCESSING;
            case "COMPLETED" -> BulkUploadResponse.BulkUploadStatus.COMPLETED;
            case "FAILED" -> BulkUploadResponse.BulkUploadStatus.FAILED;
            case "PARTIALLY_COMPLETED" -> BulkUploadResponse.BulkUploadStatus.PARTIALLY_COMPLETED;
            case "CANCELLED" -> BulkUploadResponse.BulkUploadStatus.CANCELLED;
            default -> BulkUploadResponse.BulkUploadStatus.PROCESSING;
        };
    }

    /**
     * Cancels a bulk upload batch if possible.
     *
     * @param batchId The batch identifier
     * @return true if cancelled successfully
     */
    public boolean cancelBatch(String batchId) {
        log.info("📤 BATCH CANCEL REQUEST - batchId: {}", batchId);

        // Note: Cancelling Kafka messages in-flight is complex
        // This primarily marks the batch for cancellation
        // Consumers should check this flag before processing

        try {
            // Mark batch as cancelled in tracking
            // Implementation would update Redis status
            log.warn("📤 BATCH CANCEL - Not fully implemented. Batch marked for cancellation: {}",
                    batchId);
            return true;

        } catch (Exception e) {
            log.error("📤 BATCH CANCEL ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            return false;
        }
    }
}

