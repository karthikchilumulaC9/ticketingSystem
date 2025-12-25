package org.example.distributedticketingmanagementsystem.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.kafka.event.BulkTicketUploadEvent;
import org.example.distributedticketingmanagementsystem.kafka.event.TicketCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka Producer Service for sending ticket-related events.
 *
 * Features:
 * - Asynchronous message sending with callbacks
 * - Bulk upload with chunking support
 * - Proper error handling and logging
 * - Message key generation for partition routing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketKafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.ticket-events:ticket-events}")
    private String ticketEventsTopic;

    @Value("${app.kafka.topics.bulk-upload:ticket-bulk-upload}")
    private String bulkUploadTopic;

    @Value("${app.kafka.bulk.chunk-size:100}")
    private int chunkSize;

    /**
     * Sends a ticket creation event asynchronously.
     *
     * @param request the ticket creation request
     * @param correlationId correlation ID for tracing
     * @return CompletableFuture with send result
     */
    public CompletableFuture<SendResult<String, Object>> sendTicketCreatedEvent(
            TicketCreateRequest request,
            String correlationId) {

        TicketCreatedEvent event = TicketCreatedEvent.fromRequest(request, correlationId);
        String key = request.getTicketNumber();

        log.info("📤 SENDING - Ticket creation event - topic: {}, key: {}, correlationId: {}",
                ticketEventsTopic, key, correlationId);

        return kafkaTemplate.send(ticketEventsTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("✅ SENT - Ticket event - topic: {}, partition: {}, offset: {}, key: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                key);
                    } else {
                        log.error("❌ FAILED - Ticket event - topic: {}, key: {}, error: {}",
                                ticketEventsTopic, key, ex.getMessage(), ex);
                    }
                });
    }

    /**
     * Sends multiple ticket creation events asynchronously.
     *
     * @param requests list of ticket creation requests
     * @param correlationId correlation ID for tracing
     * @return list of CompletableFutures for each send operation
     */
    public List<CompletableFuture<SendResult<String, Object>>> sendTicketCreatedEvents(
            List<TicketCreateRequest> requests,
            String correlationId) {

        log.info("📤 SENDING - {} ticket creation events - correlationId: {}",
                requests.size(), correlationId);

        return requests.stream()
                .map(request -> sendTicketCreatedEvent(request, correlationId))
                .toList();
    }

    /**
     * Initiates a bulk ticket upload via Kafka.
     * Chunks the requests and sends each chunk as a separate message.
     *
     * @param requests list of ticket creation requests
     * @param uploadedBy user ID who initiated the upload
     * @param originalFilename original filename if uploaded from file
     * @return batch ID for tracking
     */
    public String sendBulkUploadEvent(
            List<TicketCreateRequest> requests,
            String uploadedBy,
            String originalFilename) {

        String batchId = generateBatchId();
        List<List<TicketCreateRequest>> chunks = chunkList(requests, chunkSize);
        int totalChunks = chunks.size();

        log.info("📤 BULK UPLOAD - Starting batch: {}, totalTickets: {}, chunks: {}, chunkSize: {}",
                batchId, requests.size(), totalChunks, chunkSize);

        for (int i = 0; i < chunks.size(); i++) {
            BulkTicketUploadEvent event = BulkTicketUploadEvent.create(
                    batchId, i, totalChunks, chunks.get(i), uploadedBy, originalFilename);

            String key = event.getChunkKey();

            kafkaTemplate.send(bulkUploadTopic, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("✅ CHUNK SENT - batch: {}, chunk: {}/{}, partition: {}, offset: {}",
                                    event.getBatchId(),
                                    event.getChunkNumber() + 1,
                                    event.getTotalChunks(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            log.error("❌ CHUNK FAILED - batch: {}, chunk: {}/{}, error: {}",
                                    event.getBatchId(),
                                    event.getChunkNumber() + 1,
                                    event.getTotalChunks(),
                                    ex.getMessage(), ex);
                        }
                    });
        }

        log.info("📤 BULK UPLOAD - All chunks queued for batch: {}", batchId);
        return batchId;
    }

    /**
     * Generates a unique batch ID for bulk uploads.
     */
    private String generateBatchId() {
        return "BATCH-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Splits a list into chunks of specified size.
     */
    private <T> List<List<T>> chunkList(List<T> list, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            chunks.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return chunks;
    }
}

