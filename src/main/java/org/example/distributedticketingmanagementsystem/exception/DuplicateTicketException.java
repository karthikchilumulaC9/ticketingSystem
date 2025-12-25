package org.example.distributedticketingmanagementsystem.exception;

/**
 * Exception thrown when attempting to create a ticket with a duplicate ticket number.
 */
public class DuplicateTicketException extends TicketingException {

    private static final String ERROR_CODE = "DUPLICATE_TICKET";

    public DuplicateTicketException(String ticketNumber) {
        super("Ticket already exists with ticket number: " + ticketNumber, ERROR_CODE);
    }

    public DuplicateTicketException(String message, Throwable cause) {
        super(message, ERROR_CODE, cause);
    }
}

