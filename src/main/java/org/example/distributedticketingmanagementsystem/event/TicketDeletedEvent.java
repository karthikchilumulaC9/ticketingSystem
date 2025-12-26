package org.example.distributedticketingmanagementsystem.event;

import lombok.Getter;

/**
 * Event published when a ticket is successfully deleted.
 *
 * <p>This event triggers cache eviction AFTER the transaction commits,
 * ensuring we only evict cache for actually deleted tickets.</p>
 *
 * <h3>Event Flow:</h3>
 * <pre>
 * 1. TicketService.deleteTicket() removes ticket from database
 * 2. TicketDeletedEvent is published (within transaction)
 * 3. Transaction commits successfully
 * 4. TicketEventListener.handleTicketDeleted() is triggered
 * 5. Cache entry is evicted from Redis
 * </pre>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 */
@Getter
public class TicketDeletedEvent extends TicketEvent {

    /**
     * Constructs a new TicketDeletedEvent.
     *
     * @param source       The object on which the event initially occurred
     * @param ticketId     The unique identifier of the deleted ticket
     * @param ticketNumber The ticket number for cache eviction
     */
    public TicketDeletedEvent(Object source, Long ticketId, String ticketNumber) {
        super(source, ticketId, ticketNumber);
    }

    @Override
    public String toString() {
        return String.format("TicketDeletedEvent[ticketId=%d, ticketNumber=%s]",
                getTicketId(), getTicketNumber());
    }
}

