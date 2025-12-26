package org.example.distributedticketingmanagementsystem.event;

import lombok.Getter;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;

/**
 * Event published when a new ticket is successfully created.
 *
 * <p>This event is published within the transaction boundary but processed
 * by {@link TicketEventListener} only AFTER the transaction commits successfully.</p>
 *
 * <h3>Event Flow:</h3>
 * <pre>
 * 1. TicketService.createTicket() saves ticket to database
 * 2. TicketCreatedEvent is published (within transaction)
 * 3. Transaction commits successfully
 * 4. TicketEventListener.handleTicketCreated() is triggered
 * 5. Ticket is cached in Redis
 * </pre>
 *
 * <h3>Benefits:</h3>
 * <ul>
 *   <li>Cache is only updated after successful DB commit</li>
 *   <li>If transaction rolls back, cache remains unchanged</li>
 *   <li>No distributed transaction complexity</li>
 * </ul>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 */
@Getter
public class TicketCreatedEvent extends TicketEvent {

    /**
     * The complete ticket DTO to be cached.
     */
    private final TicketDTO ticketDTO;

    /**
     * Constructs a new TicketCreatedEvent.
     *
     * @param source    The object on which the event initially occurred
     * @param ticketId  The unique identifier of the created ticket
     * @param ticketDTO The complete ticket data transfer object
     */
    public TicketCreatedEvent(Object source, Long ticketId, TicketDTO ticketDTO) {
        super(source, ticketId, ticketDTO.getTicketNumber());
        this.ticketDTO = ticketDTO;
    }

    @Override
    public String toString() {
        return String.format("TicketCreatedEvent[ticketId=%d, ticketNumber=%s]",
                getTicketId(), getTicketNumber());
    }
}

