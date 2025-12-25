package org.example.distributedticketingmanagementsystem.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.kafka.event.BulkTicketUploadEvent;
import org.example.distributedticketingmanagementsystem.kafka.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise-level Kafka Producer for bulk ticket processing.
 * Handles chunking, error handling, and delivery tracking.
 *
 * <p>Features:
 * <ul>
 *   <li>Chunked message sending for large batches</li>
 *   <li>Comprehensive error handling and categorization</li>
 *   <li>Async sending with completion tracking</li>
 *   <li>Configurable timeouts and retry behavior</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li>Topic: ticket.bulk.requests (5 partitions)</li>
 *   <li>Chunk size: 100 records</li>
 *   <li>Send timeout: 30 seconds</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkTicketKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.bulk-requests:ticket.bulk.requests}")
    private String bulkRequestsTopic;

    @Value("${app.kafka.bulk.chunk-size:100}")
    private int chunkSize;

    @Value("${app.kafka.producer.send-timeout-seconds:30}")
    private int sendTimeoutSeconds;

    @Value("${app.kafka.producer.async:true}")
    private boolean asyncSend;

    /**
     * Sends a bulk ticket upload for processing via Kafka.
     *
     * <p>Processing:
     * <ol>
     *   <li>Validate input</li>
     *   <li>Generate batch ID</li>
     *   <li>Chunk the requests</li>
     *   <li>Send each chunk to Kafka</li>
     *   <li>Track delivery status</li>
     * </ol>
     *
     * @param requests       List of ticket creation requests
     * @param uploadedBy     User who initiated the upload
     * @param originalFilename Original filename
     * @return Batch ID for tracking
     * @throws KafkaBulkProcessingException if sending fails
     */
    public String sendBulkUpload(List<TicketCreateRequest> requests,
                                  String uploadedBy,
                                  String originalFilename) {

        // Validate input
        validateInput(requests, uploadedBy);

        // Generate batch ID
        String batchId = generateBatchId();

        log.info("📤 BULK UPLOAD INITIATED - batchId: {}, tickets: {}, uploadedBy: {}, file: {}",
                batchId, requests.size(), uploadedBy, originalFilename);

        try {
            // Chunk the requests
            List<List<TicketCreateRequest>> chunks = chunkList(requests, chunkSize);
            int totalChunks = chunks.size();

            log.info("📤 CHUNKING COMPLETE - batchId: {}, chunks: {}, chunkSize: {}",
                    batchId, totalChunks, chunkSize);

            // Send tracking
            AtomicInteger sentChunks = new AtomicInteger(0);
            AtomicInteger failedChunks = new AtomicInteger(0);
            List<ChunkSendResult> chunkResults = new ArrayList<>();

            // Send each chunk
            for (int i = 0; i < chunks.size(); i++) {
                List<TicketCreateRequest> chunk = chunks.get(i);
                final int chunkNumber = i;

                try {
                    ChunkSendResult result = sendChunk(
                            batchId, chunkNumber, totalChunks,
                            chunk, uploadedBy, originalFilename
                    );
                    chunkResults.add(result);

                    if (result.success()) {
                        sentChunks.incrementAndGet();
                    } else {
                        failedChunks.incrementAndGet();
                    }

                } catch (Exception e) {
                    failedChunks.incrementAndGet();
                    chunkResults.add(new ChunkSendResult(chunkNumber, false, e.getMessage()));

                    log.error("❌ CHUNK SEND FAILED - batchId: {}, chunk: {}/{}, error: {}",
                            batchId, chunkNumber + 1, totalChunks, e.getMessage());
                }
            }

            // Check results
            if (failedChunks.get() == totalChunks) {
                throw new KafkaBulkProcessingException(
                        "All chunks failed to send",
                        batchId, -1,
                        BulkProcessingErrorCode.KAFKA_PRODUCER_ERROR,
                        true, requests.size()
                );
            }

            if (failedChunks.get() > 0) {
                log.warn("⚠️ PARTIAL SEND - batchId: {}, sent: {}/{}, failed: {}",
                        batchId, sentChunks.get(), totalChunks, failedChunks.get());
            }

            log.info("✅ BULK UPLOAD QUEUED - batchId: {}, sentChunks: {}/{}",
                    batchId, sentChunks.get(), totalChunks);

            return batchId;

        } catch (KafkaBulkProcessingException e) {
            throw e;

        } catch (KafkaException e) {
            log.error("❌ KAFKA ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            throw new KafkaBulkProcessingException(
                    "Kafka error during bulk upload: " + e.getMessage(),
                    batchId, -1,
                    BulkProcessingErrorCode.KAFKA_PRODUCER_ERROR,
                    true, requests.size(), e
            );

        } catch (Exception e) {
            log.error("❌ UNEXPECTED ERROR - batchId: {}, error: {}", batchId, e.getMessage(), e);
            throw new KafkaBulkProcessingException(
                    "Unexpected error during bulk upload: " + e.getMessage(),
                    batchId, e
            );
        }
    }

    /**
     * Validates input parameters.
     */
    private void validateInput(List<TicketCreateRequest> requests, String uploadedBy) {
        if (requests == null) {
            throw new KafkaBulkProcessingException(
                    "Ticket requests list cannot be null",
                    null,
                    BulkProcessingErrorCode.NULL_REQUEST
            );
        }

        if (requests.isEmpty()) {
            throw new KafkaBulkProcessingException(
                    "Ticket requests list cannot be empty",
                    null,
                    BulkProcessingErrorCode.EMPTY_FILE
            );
        }

        if (!StringUtils.hasText(uploadedBy)) {
            log.warn("⚠️ Missing uploadedBy, defaulting to 'system'");
        }
    }

    /**
     * Sends a single chunk to Kafka.
     */
    private ChunkSendResult sendChunk(String batchId, int chunkNumber, int totalChunks,
                                       List<TicketCreateRequest> tickets,
                                       String uploadedBy, String originalFilename) {

        // Create event
        BulkTicketUploadEvent event = BulkTicketUploadEvent.create(
                batchId, chunkNumber, totalChunks,
                tickets, uploadedBy, originalFilename
        );

        String key = event.getChunkKey();

        log.debug("📤 SENDING CHUNK - batch: {}, chunk: {}/{}, tickets: {}, key: {}",
                batchId, chunkNumber + 1, totalChunks, tickets.size(), key);

        try {
            if (asyncSend) {
                // Async send with callback
                CompletableFuture<SendResult<String, Object>> future =
                        kafkaTemplate.send(bulkRequestsTopic, key, event);

                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("❌ ASYNC CHUNK FAILED - batch: {}, chunk: {}/{}, error: {}",
                                batchId, chunkNumber + 1, totalChunks, ex.getMessage());
                    } else {
                        log.info("✅ ASYNC CHUNK SENT - batch: {}, chunk: {}/{}, " +
                                 "partition: {}, offset: {}",
                                batchId, chunkNumber + 1, totalChunks,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

                // For tracking, we consider it sent once queued
                return new ChunkSendResult(chunkNumber, true, null);

            } else {
                // Sync send with timeout
                CompletableFuture<SendResult<String, Object>> future =
                        kafkaTemplate.send(bulkRequestsTopic, key, event);

                SendResult<String, Object> result = future.get(sendTimeoutSeconds, TimeUnit.SECONDS);

                log.info("✅ SYNC CHUNK SENT - batch: {}, chunk: {}/{}, partition: {}, offset: {}",
                        batchId, chunkNumber + 1, totalChunks,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());

                return new ChunkSendResult(chunkNumber, true, null);
            }

        } catch (TimeoutException e) {
            String error = String.format("Timeout sending chunk %d after %d seconds",
                    chunkNumber, sendTimeoutSeconds);
            log.error("⏱️ TIMEOUT - batch: {}, chunk: {}/{}, error: {}",
                    batchId, chunkNumber + 1, totalChunks, error);

            throw new KafkaProducerException(error, bulkRequestsTopic, key, batchId, e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String error = "Interrupted while sending chunk " + chunkNumber;
            log.error("⚡ INTERRUPTED - batch: {}, chunk: {}/{}", batchId, chunkNumber + 1, totalChunks);

            throw new KafkaProducerException(error, bulkRequestsTopic, key, batchId, e);

        } catch (Exception e) {
            String error = "Failed to send chunk " + chunkNumber + ": " + e.getMessage();
            log.error("❌ SEND FAILED - batch: {}, chunk: {}/{}, error: {}",
                    batchId, chunkNumber + 1, totalChunks, e.getMessage(), e);

            throw new KafkaProducerException(error, bulkRequestsTopic, key, batchId, e);
        }
    }

    /**
     * Sends a single ticket event (for non-bulk operations).
     */
    public CompletableFuture<SendResult<String, Object>> sendTicketEvent(
            TicketCreateRequest request, String correlationId) {

        if (request == null) {
            throw new KafkaProducerException(
                    "Ticket request cannot be null",
                    bulkRequestsTopic, null
            );
        }

        String key = request.getTicketNumber();

        log.debug("📤 SENDING TICKET EVENT - ticketNumber: {}, correlationId: {}",
                key, correlationId);

        try {
            return kafkaTemplate.send(bulkRequestsTopic, key, request)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("❌ TICKET EVENT FAILED - ticketNumber: {}, error: {}",
                                    key, ex.getMessage());
                        } else {
                            log.info("✅ TICKET EVENT SENT - ticketNumber: {}, partition: {}, offset: {}",
                                    key,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });

        } catch (Exception e) {
            log.error("❌ TICKET EVENT ERROR - ticketNumber: {}, error: {}",
                    key, e.getMessage(), e);
            throw new KafkaProducerException(
                    "Failed to send ticket event: " + e.getMessage(),
                    bulkRequestsTopic, key, correlationId, e
            );
        }
    }

    /**
     * Generates a unique batch ID.
     */
    private String generateBatchId() {
        return String.format("BATCH-%d-%s",
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    /**
     * Splits a list into chunks.
     */
    private <T> List<List<T>> chunkList(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return chunks;
    }

    /**
     * Result of sending a chunk.
     */
    private record ChunkSendResult(int chunkNumber, boolean success, String error) {}
}

