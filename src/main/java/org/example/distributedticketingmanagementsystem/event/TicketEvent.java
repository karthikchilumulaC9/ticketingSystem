package org.example.distributedticketingmanagementsystem.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Base class for all ticket-related domain events.
 *
 * <p>These events are published during ticket operations and processed
 * AFTER the database transaction commits successfully. This ensures
 * that cache operations are only performed for persisted data.</p>
 *
 * <h3>Design Rationale:</h3>
 * <ul>
 *   <li>Decouples caching from business logic</li>
 *   <li>Ensures cache consistency with database state</li>
 *   <li>Follows Domain-Driven Design event patterns</li>
 *   <li>Enables future extensibility (audit logging, notifications, etc.)</li>
 * </ul>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 */
@Getter
public abstract class TicketEvent extends ApplicationEvent {

    /**
     * The unique identifier of the ticket involved in this event.
     */
    private final Long ticketId;

    /**
     * The ticket number (business key) for cache key management.
     */
    private final String ticketNumber;

    /**
     * Constructs a new TicketEvent.
     *
     * @param source       The object on which the event initially occurred
     * @param ticketId     The unique identifier of the ticket
     * @param ticketNumber The ticket number (business key)
     */
    protected TicketEvent(Object source, Long ticketId, String ticketNumber) {
        super(source);
        this.ticketId = ticketId;
        this.ticketNumber = ticketNumber;
    }
}

