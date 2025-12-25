package org.example.distributedticketingmanagementsystem.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException;
import org.example.distributedticketingmanagementsystem.kafka.event.BulkTicketUploadEvent;
import org.example.distributedticketingmanagementsystem.kafka.event.TicketCreatedEvent;
import org.example.distributedticketingmanagementsystem.kafka.service.BulkUploadTrackingService;
import org.example.distributedticketingmanagementsystem.service.TicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka Consumer Service for processing ticket-related events.
 *
 * Features:
 * - @KafkaListener annotation-driven consumption
 * - Manual acknowledgment for reliability
 * - Dead Letter Topic support for failed messages
 * - Proper error handling with logging
 * - Integration with tracking service for bulk uploads
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketKafkaConsumer {

    private final TicketService ticketService;
    private final BulkUploadTrackingService trackingService;

    /**
     * Consumes ticket creation events from the main topic.
     * Processes each event and creates the ticket in the database.
     *
     * @param event the ticket creation event
     * @param key message key (ticket number)
     * @param partition partition number
     * @param offset message offset
     * @param acknowledgment for manual acknowledgment
     */
    @KafkaListener(
            topics = "${app.kafka.topics.ticket-events:ticket-events}",
            groupId = "${spring.kafka.consumer.group-id:ticketing-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTicketCreatedEvent(
            @Payload TicketCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("📥 RECEIVED - Ticket event - partition: {}, offset: {}, key: {}, eventId: {}",
                partition, offset, key, event.getEventId());

        try {
            // Process the ticket creation
            TicketDTO createdTicket = ticketService.createTicket(event.toCreateRequest());

            log.info("✅ PROCESSED - Ticket created - id: {}, ticketNumber: {}, correlationId: {}",
                    createdTicket.getId(), createdTicket.getTicketNumber(), event.getCorrelationId());

            // Acknowledge successful processing
            acknowledgment.acknowledge();

        } catch (DuplicateTicketException e) {
            // Duplicate tickets should not be retried
            log.warn("⚠️ DUPLICATE - Ticket already exists - ticketNumber: {}, acknowledging anyway",
                    event.getTicketNumber());
            acknowledgment.acknowledge();

        } catch (IllegalArgumentException e) {
            // Validation errors should not be retried
            log.error("❌ VALIDATION ERROR - Ticket creation failed - ticketNumber: {}, error: {}",
                    event.getTicketNumber(), e.getMessage());
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Other errors will trigger retry and eventually DLT
            log.error("❌ PROCESSING ERROR - Ticket creation failed - ticketNumber: {}, error: {}",
                    event.getTicketNumber(), e.getMessage(), e);
            throw e; // Re-throw to trigger error handler and DLT
        }
    }

    /**
     * Consumes bulk upload events for processing batches of tickets.
     * Each chunk is processed and results are tracked.
     *
     * @param event the bulk upload event
     * @param key message key (chunk key)
     * @param partition partition number
     * @param offset message offset
     * @param acknowledgment for manual acknowledgment
     */
    @KafkaListener(
            topics = "${app.kafka.topics.bulk-upload:ticket-bulk-upload}",
            groupId = "${spring.kafka.consumer.group-id:ticketing-group}-bulk",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBulkUploadEvent(
            @Payload BulkTicketUploadEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("📥 BULK RECEIVED - batch: {}, chunk: {}/{}, tickets: {}, partition: {}, offset: {}",
                event.getBatchId(),
                event.getChunkNumber() + 1,
                event.getTotalChunks(),
                event.getTickets().size(),
                partition, offset);

        try {
            // Initialize tracking if this is the first chunk we've seen
            trackingService.initializeBatch(event.getBatchId(), event.getTotalChunks(),
                    event.getTickets().size() * event.getTotalChunks());

            // Process each ticket in the chunk
            int successCount = 0;
            int failureCount = 0;

            for (var request : event.getTickets()) {
                try {
                    TicketDTO createdTicket = ticketService.createTicket(request);
                    trackingService.recordSuccess(event.getBatchId(), request.getTicketNumber());
                    successCount++;

                    log.debug("✅ BULK ITEM - Created ticket: {}", createdTicket.getTicketNumber());

                } catch (DuplicateTicketException e) {
                    trackingService.recordFailure(event.getBatchId(), request.getTicketNumber(),
                            "DUPLICATE", e.getMessage());
                    failureCount++;
                    log.warn("⚠️ BULK DUPLICATE - ticketNumber: {}", request.getTicketNumber());

                } catch (IllegalArgumentException e) {
                    trackingService.recordFailure(event.getBatchId(), request.getTicketNumber(),
                            "VALIDATION_ERROR", e.getMessage());
                    failureCount++;
                    log.warn("⚠️ BULK VALIDATION - ticketNumber: {}, error: {}",
                            request.getTicketNumber(), e.getMessage());

                } catch (Exception e) {
                    trackingService.recordFailure(event.getBatchId(), request.getTicketNumber(),
                            "PROCESSING_ERROR", e.getMessage());
                    failureCount++;
                    log.error("❌ BULK ITEM ERROR - ticketNumber: {}, error: {}",
                            request.getTicketNumber(), e.getMessage());
                }
            }

            // Mark chunk as completed
            trackingService.completeChunk(event.getBatchId(), event.getChunkNumber());

            log.info("✅ BULK CHUNK PROCESSED - batch: {}, chunk: {}/{}, success: {}, failed: {}",
                    event.getBatchId(),
                    event.getChunkNumber() + 1,
                    event.getTotalChunks(),
                    successCount, failureCount);

            // Acknowledge the message
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("❌ BULK CHUNK ERROR - batch: {}, chunk: {}/{}, error: {}",
                    event.getBatchId(),
                    event.getChunkNumber() + 1,
                    event.getTotalChunks(),
                    e.getMessage(), e);
            throw e; // Re-throw to trigger error handler and DLT
        }
    }

    /**
     * Consumes messages from the Dead Letter Topic for ticket events.
     * Logs failed messages for analysis and potential manual reprocessing.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.ticket-events:ticket-events}.DLT",
            groupId = "${spring.kafka.consumer.group-id:ticketing-group}-dlt"
    )
    public void consumeTicketEventsDlt(
            ConsumerRecord<String, Object> record,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.error("☠️ DLT RECEIVED - topic: {}, partition: {}, offset: {}, key: {}, value: {}",
                record.topic(), partition, offset, record.key(), record.value());

        // Store in DLT tracking for later analysis/reprocessing
        trackingService.recordDltMessage(record.topic(), record.key(), record.value());
    }

    /**
     * Consumes messages from the Dead Letter Topic for bulk uploads.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.bulk-upload:ticket-bulk-upload}.DLT",
            groupId = "${spring.kafka.consumer.group-id:ticketing-group}-bulk-dlt"
    )
    public void consumeBulkUploadDlt(
            ConsumerRecord<String, Object> record,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.error("☠️ BULK DLT RECEIVED - topic: {}, partition: {}, offset: {}, key: {}",
                record.topic(), partition, offset, record.key());

        // Store in DLT tracking for later analysis/reprocessing
        trackingService.recordDltMessage(record.topic(), record.key(), record.value());
    }
}

