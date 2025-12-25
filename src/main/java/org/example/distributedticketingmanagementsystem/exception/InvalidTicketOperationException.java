package org.example.distributedticketingmanagementsystem.exception;

/**
 * Exception thrown when an invalid operation is attempted on a ticket.
 * For example, trying to close an already closed ticket.
 */
public class InvalidTicketOperationException extends TicketingException {

    private static final String ERROR_CODE = "INVALID_TICKET_OPERATION";

    public InvalidTicketOperationException(String message) {
        super(message, ERROR_CODE);
    }

    public InvalidTicketOperationException(String operation, String reason) {
        super("Cannot perform operation '" + operation + "': " + reason, ERROR_CODE);
    }

    public InvalidTicketOperationException(String message, Throwable cause) {
        super(message, ERROR_CODE, cause);
    }
}

