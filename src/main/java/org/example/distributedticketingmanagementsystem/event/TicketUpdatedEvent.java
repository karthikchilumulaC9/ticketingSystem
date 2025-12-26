package org.example.distributedticketingmanagementsystem.event;

import lombok.Getter;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;

/**
 * Event published when an existing ticket is successfully updated.
 *
 * <p>This event triggers cache eviction of the old data and caching
 * of the updated ticket data AFTER the transaction commits.</p>
 *
 * <h3>Event Flow:</h3>
 * <pre>
 * 1. TicketService.updateTicket() updates ticket in database
 * 2. TicketUpdatedEvent is published (within transaction)
 * 3. Transaction commits successfully
 * 4. TicketEventListener.handleTicketUpdated() is triggered
 * 5. Old cache entry is evicted, new data is cached
 * </pre>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 */
@Getter
public class TicketUpdatedEvent extends TicketEvent {

    /**
     * The updated ticket DTO to be cached.
     */
    private final TicketDTO ticketDTO;

    /**
     * Constructs a new TicketUpdatedEvent.
     *
     * @param source    The object on which the event initially occurred
     * @param ticketId  The unique identifier of the updated ticket
     * @param ticketDTO The updated ticket data transfer object
     */
    public TicketUpdatedEvent(Object source, Long ticketId, TicketDTO ticketDTO) {
        super(source, ticketId, ticketDTO.getTicketNumber());
        this.ticketDTO = ticketDTO;
    }

    @Override
    public String toString() {
        return String.format("TicketUpdatedEvent[ticketId=%d, ticketNumber=%s]",
                getTicketId(), getTicketNumber());
    }
}

