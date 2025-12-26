package org.example.distributedticketingmanagementsystem.event;

import lombok.Getter;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;

/**
 * Event published when a ticket's cache needs to be populated.
 *
 * <p>This event is used for cache-aside pattern when a ticket is
 * fetched from the database (cache miss scenario).</p>
 *
 * <h3>Event Flow:</h3>
 * <pre>
 * 1. TicketService.getTicketById() has cache miss
 * 2. Ticket is fetched from database
 * 3. TicketCacheEvent is published
 * 4. TicketEventListener.handleTicketCache() caches the ticket
 * </pre>
 *
 * <p><strong>Note:</strong> Unlike create/update/delete events, this event
 * is processed in AFTER_COMMIT phase for read-only transactions as well.</p>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 */
@Getter
public class TicketCacheEvent extends TicketEvent {

    /**
     * The ticket DTO to be cached.
     */
    private final TicketDTO ticketDTO;

    /**
     * Constructs a new TicketCacheEvent.
     *
     * @param source    The object on which the event initially occurred
     * @param ticketId  The unique identifier of the ticket
     * @param ticketDTO The ticket data transfer object to cache
     */
    public TicketCacheEvent(Object source, Long ticketId, TicketDTO ticketDTO) {
        super(source, ticketId, ticketDTO.getTicketNumber());
        this.ticketDTO = ticketDTO;
    }

    @Override
    public String toString() {
        return String.format("TicketCacheEvent[ticketId=%d, ticketNumber=%s]",
                getTicketId(), getTicketNumber());
    }
}

