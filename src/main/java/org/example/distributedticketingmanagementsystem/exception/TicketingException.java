package org.example.distributedticketingmanagementsystem.exception;

/**
 * Base exception class for all ticket-related exceptions.
 * Provides a foundation for enterprise-level exception handling.
 */
public abstract class TicketingException extends RuntimeException {

    private final String errorCode;

    protected TicketingException(String message) {
        super(message);
        this.errorCode = "TICKETING_ERROR";
    }

    protected TicketingException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    protected TicketingException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "TICKETING_ERROR";
    }

    protected TicketingException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

