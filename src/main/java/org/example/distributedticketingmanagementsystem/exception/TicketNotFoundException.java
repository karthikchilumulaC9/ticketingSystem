package org.example.distributedticketingmanagementsystem.exception;

/**
 * Exception thrown when a ticket is not found in the system.
 */
public class TicketNotFoundException extends TicketingException {

    private static final String ERROR_CODE = "TICKET_NOT_FOUND";

    public TicketNotFoundException(String message) {
        super(message, ERROR_CODE);
    }

    public TicketNotFoundException(Long id) {
        super("Ticket not found with id: " + id, ERROR_CODE);
    }

    public TicketNotFoundException(String field, String value) {
        super("Ticket not found with " + field + ": " + value, ERROR_CODE);
    }
}

