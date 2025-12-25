package org.example.distributedticketingmanagementsystem.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Event published when a ticket creation is requested.
 * Used for asynchronous ticket creation via Kafka.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TicketCreatedEvent extends TicketEvent {

    private static final String EVENT_TYPE = "TICKET_CREATED";

    /**
     * Ticket number for the new ticket.
     */
    private String ticketNumber;

    /**
     * Title of the ticket.
     */
    private String title;

    /**
     * Description of the ticket.
     */
    private String description;

    /**
     * Initial status of the ticket.
     */
    private String status;

    /**
     * Priority level.
     */
    private String priority;

    /**
     * Customer ID who raised the ticket.
     */
    private Long customerId;

    /**
     * User ID to whom the ticket is assigned.
     */
    private Integer assignedTo;

    /**
     * Creates a TicketCreatedEvent from a TicketCreateRequest.
     */
    public static TicketCreatedEvent fromRequest(TicketCreateRequest request, String correlationId) {
        TicketCreatedEvent event = new TicketCreatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(EVENT_TYPE);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);
        event.setSource("ticketing-service");

        event.setTicketNumber(request.getTicketNumber());
        event.setTitle(request.getTitle());
        event.setDescription(request.getDescription());
        event.setStatus(request.getStatus());
        event.setPriority(request.getPriority());
        event.setCustomerId(request.getCustomerId());
        event.setAssignedTo(request.getAssignedTo());

        return event;
    }

    /**
     * Converts back to TicketCreateRequest for processing.
     */
    public TicketCreateRequest toCreateRequest() {
        return TicketCreateRequest.builder()
                .ticketNumber(this.ticketNumber)
                .title(this.title)
                .description(this.description)
                .status(this.status)
                .priority(this.priority)
                .customerId(this.customerId)
                .assignedTo(this.assignedTo)
                .build();
    }
}

