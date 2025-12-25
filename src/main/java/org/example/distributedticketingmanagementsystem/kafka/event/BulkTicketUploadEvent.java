package org.example.distributedticketingmanagementsystem.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Event for bulk ticket upload processing via Kafka.
 * Contains a batch of ticket creation requests and metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BulkTicketUploadEvent extends TicketEvent {

    private static final String EVENT_TYPE = "BULK_TICKET_UPLOAD";

    /**
     * Unique batch ID for tracking this bulk upload.
     */
    private String batchId;

    /**
     * Chunk number for large uploads (0-based).
     */
    private int chunkNumber;

    /**
     * Total number of chunks in this batch.
     */
    private int totalChunks;

    /**
     * List of ticket creation requests in this chunk.
     */
    private List<TicketCreateRequest> tickets;

    /**
     * User ID who initiated the upload.
     */
    private String uploadedBy;

    /**
     * Original filename if uploaded from file.
     */
    private String originalFilename;

    /**
     * Creates a BulkTicketUploadEvent for a chunk of tickets.
     */
    public static BulkTicketUploadEvent create(
            String batchId,
            int chunkNumber,
            int totalChunks,
            List<TicketCreateRequest> tickets,
            String uploadedBy,
            String originalFilename) {

        BulkTicketUploadEvent event = new BulkTicketUploadEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(EVENT_TYPE);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(batchId);
        event.setSource("ticketing-service");

        event.setBatchId(batchId);
        event.setChunkNumber(chunkNumber);
        event.setTotalChunks(totalChunks);
        event.setTickets(tickets);
        event.setUploadedBy(uploadedBy);
        event.setOriginalFilename(originalFilename);

        return event;
    }

    /**
     * Get a unique key for this chunk (used as Kafka message key).
     */
    public String getChunkKey() {
        return batchId + "-CHUNK-" + chunkNumber;
    }
}

