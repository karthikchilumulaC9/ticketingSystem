package org.example.distributedticketingmanagementsystem.exception;

import lombok.Getter;

/**
 * Exception thrown when a required request body or field is null or missing.
 * Provides field-level information for better error reporting.
 *
 * @author Ticketing System Team
 * @version 2.0
 * @since 2024-12-24
 */
@Getter
public class NullRequestException extends TicketingException {

    private static final String ERROR_CODE = "NULL_REQUEST";

    /**
     * The field name that was null.
     */
    private final String field;

    /**
     * Creates a NullRequestException for a null resource.
     *
     * @param resourceName the name of the resource that was null
     */
    public NullRequestException(String resourceName) {
        super(String.format("%s request cannot be null or empty", resourceName), ERROR_CODE);
        this.field = resourceName;
    }

    /**
     * Creates a NullRequestException with a custom message.
     *
     * @param resourceName the name of the resource/field that was null
     * @param message      custom error message
     */
    public NullRequestException(String resourceName, String message) {
        super(message, ERROR_CODE);
        this.field = resourceName;
    }

    /**
     * Creates a NullRequestException with field, message, and cause.
     *
     * @param resourceName the name of the resource/field that was null
     * @param message      custom error message
     * @param cause        the underlying cause
     */
    public NullRequestException(String resourceName, String message, Throwable cause) {
        super(message, ERROR_CODE, cause);
        this.field = resourceName;
    }
}
