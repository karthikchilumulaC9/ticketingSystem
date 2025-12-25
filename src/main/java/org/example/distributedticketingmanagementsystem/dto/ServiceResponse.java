package org.example.distributedticketingmanagementsystem.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Generic service response wrapper for ticket operations.
 * Provides a consistent structure for both successful and failed operations.
 *
 * <p>Usage:
 * <pre>
 * // Success
 * ServiceResponse.success(ticketDTO);
 *
 * // Error
 * ServiceResponse.error("VALIDATION_ERROR", "Ticket number is required");
 *
 * // Not found
 * ServiceResponse.notFound("Ticket", "id", 123L);
 * </pre>
 *
 * @param <T> The type of data contained in the response
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Indicates if the operation was successful.
     */
    private boolean success;

    /**
     * The data returned on success.
     */
    private T data;

    /**
     * Error code for categorization.
     */
    private String errorCode;

    /**
     * Human-readable error message.
     */
    private String errorMessage;

    /**
     * Field that caused the error (for validation errors).
     */
    private String errorField;

    /**
     * Timestamp of the response.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    // ==================== Error Codes ====================

    public static final String ERROR_NULL_REQUEST = "NULL_REQUEST";
    public static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";
    public static final String ERROR_DUPLICATE = "DUPLICATE";
    public static final String ERROR_INVALID_OPERATION = "INVALID_OPERATION";
    public static final String ERROR_DATABASE = "DATABASE_ERROR";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    // ==================== Factory Methods ====================

    /**
     * Creates a successful response with data.
     */
    public static <T> ServiceResponse<T> success(T data) {
        return ServiceResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a successful response without data.
     */
    public static <T> ServiceResponse<T> success() {
        return ServiceResponse.<T>builder()
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response.
     */
    public static <T> ServiceResponse<T> error(String errorCode, String errorMessage) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response with field information.
     */
    public static <T> ServiceResponse<T> error(String errorCode, String errorMessage, String errorField) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .errorField(errorField)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a null request error response.
     */
    public static <T> ServiceResponse<T> nullRequest(String field, String message) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_NULL_REQUEST)
                .errorMessage(message)
                .errorField(field)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a not found error response.
     */
    public static <T> ServiceResponse<T> notFound(String entity, String field, Object value) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_NOT_FOUND)
                .errorMessage(String.format("%s not found with %s: %s", entity, field, value))
                .errorField(field)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a duplicate error response.
     */
    public static <T> ServiceResponse<T> duplicate(String entity, String field, Object value) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_DUPLICATE)
                .errorMessage(String.format("%s already exists with %s: %s", entity, field, value))
                .errorField(field)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a validation error response.
     */
    public static <T> ServiceResponse<T> validationError(String field, String message) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_VALIDATION)
                .errorMessage(message)
                .errorField(field)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an invalid operation error response.
     */
    public static <T> ServiceResponse<T> invalidOperation(String message) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_INVALID_OPERATION)
                .errorMessage(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a database error response.
     */
    public static <T> ServiceResponse<T> databaseError(String message) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_DATABASE)
                .errorMessage(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an internal error response.
     */
    public static <T> ServiceResponse<T> internalError(String message) {
        return ServiceResponse.<T>builder()
                .success(false)
                .errorCode(ERROR_INTERNAL)
                .errorMessage(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ==================== Utility Methods ====================

    /**
     * Checks if the response has data.
     */
    public boolean hasData() {
        return success && data != null;
    }

    /**
     * Checks if this is a not found error.
     */
    public boolean isNotFound() {
        return !success && ERROR_NOT_FOUND.equals(errorCode);
    }

    /**
     * Checks if this is a validation error.
     */
    public boolean isValidationError() {
        return !success && (ERROR_VALIDATION.equals(errorCode) || ERROR_NULL_REQUEST.equals(errorCode));
    }

    /**
     * Checks if this is a duplicate error.
     */
    public boolean isDuplicate() {
        return !success && ERROR_DUPLICATE.equals(errorCode);
    }

    /**
     * Gets the data or throws an exception if not successful.
     */
    public T getDataOrThrow() {
        if (!success) {
            throw new RuntimeException(errorMessage);
        }
        return data;
    }

    /**
     * Gets the data or returns a default value.
     */
    public T getDataOrDefault(T defaultValue) {
        return hasData() ? data : defaultValue;
    }
}

