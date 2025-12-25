package org.example.distributedticketingmanagementsystem.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Base class for all ticket-related Kafka events.
 * Contains common fields for event tracking and correlation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class TicketEvent {

    /**
     * Unique event ID for tracking and deduplication.
     */
    private String eventId;

    /**
     * Event type identifier.
     */
    private String eventType;

    /**
     * Timestamp when the event was created.
     */
    private LocalDateTime timestamp;

    /**
     * Correlation ID for tracing across services.
     */
    private String correlationId;

    /**
     * Source system/service that generated the event.
     */
    private String source;
}

