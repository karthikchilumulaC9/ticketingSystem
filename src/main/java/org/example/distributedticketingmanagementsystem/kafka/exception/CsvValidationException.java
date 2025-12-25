package org.example.distributedticketingmanagementsystem.kafka.exception;

import lombok.Getter;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when CSV validation fails during bulk upload.
 * Contains detailed information about all validation errors found.
 *
 * <p>Enterprise features:
 * <ul>
 *   <li>Collects all validation errors (not just first)</li>
 *   <li>Line-by-line error tracking</li>
 *   <li>Structured error reporting</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024-12-24
 */
@Getter
public class CsvValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Original filename being processed.
     */
    private final String filename;

    /**
     * List of validation errors with line numbers.
     */
    private final List<ValidationError> validationErrors;

    /**
     * Total number of rows attempted to process.
     */
    private final int totalRows;

    /**
     * Number of valid rows found.
     */
    private final int validRows;

    /**
     * Timestamp when validation occurred.
     */
    private final LocalDateTime timestamp;

    /**
     * Constructs a CsvValidationException with validation errors.
     *
     * @param message           Summary error message
     * @param filename          Original filename
     * @param validationErrors  List of validation errors
     * @param totalRows         Total rows attempted
     * @param validRows         Number of valid rows
     */
    public CsvValidationException(String message, String filename,
                                   List<ValidationError> validationErrors,
                                   int totalRows, int validRows) {
        super(message);
        this.filename = filename;
        this.validationErrors = validationErrors != null ?
                new ArrayList<>(validationErrors) : new ArrayList<>();
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Constructs a CsvValidationException with a single error.
     */
    public CsvValidationException(String message, String filename, int lineNumber,
                                   String field, String errorDetail) {
        super(message);
        this.filename = filename;
        this.validationErrors = new ArrayList<>();
        this.validationErrors.add(new ValidationError(lineNumber, field, errorDetail, null));
        this.totalRows = 0;
        this.validRows = 0;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Constructs a CsvValidationException for missing columns.
     */
    public CsvValidationException(String message, String filename,
                                   List<String> missingColumns) {
        super(message);
        this.filename = filename;
        this.validationErrors = missingColumns.stream()
                .map(col -> new ValidationError(1, col, "Missing required column", null))
                .toList();
        this.totalRows = 0;
        this.validRows = 0;
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Gets an unmodifiable view of validation errors.
     */
    public List<ValidationError> getErrors() {
        return Collections.unmodifiableList(validationErrors);
    }

    /**
     * Gets the count of invalid rows.
     */
    public int getInvalidRowCount() {
        return validationErrors.size();
    }

    /**
     * Checks if there are any validation errors.
     */
    public boolean hasErrors() {
        return !validationErrors.isEmpty();
    }

    /**
     * Gets a formatted summary of all errors.
     */
    public String getErrorSummary() {
        if (validationErrors.isEmpty()) {
            return "No validation errors";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("CSV Validation Failed: %d error(s) in file '%s'%n",
                validationErrors.size(), filename));
        sb.append(String.format("Total rows: %d, Valid: %d, Invalid: %d%n",
                totalRows, validRows, validationErrors.size()));

        int displayLimit = Math.min(10, validationErrors.size());
        for (int i = 0; i < displayLimit; i++) {
            ValidationError error = validationErrors.get(i);
            sb.append(String.format("  Line %d: [%s] %s%n",
                    error.lineNumber(), error.field(), error.message()));
        }

        if (validationErrors.size() > displayLimit) {
            sb.append(String.format("  ... and %d more errors%n",
                    validationErrors.size() - displayLimit));
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return getErrorSummary();
    }

    /**
     * Represents a single validation error.
     */
    public record ValidationError(
            int lineNumber,
            String field,
            String message,
            String actualValue
    ) {
        /**
         * Creates a ValidationError with position context.
         */
        public static ValidationError of(int lineNumber, String field,
                                          String message, String actualValue) {
            return new ValidationError(lineNumber, field, message, actualValue);
        }

        /**
         * Creates a ValidationError without value context.
         */
        public static ValidationError of(int lineNumber, String field, String message) {
            return new ValidationError(lineNumber, field, message, null);
        }

        @Override
        public String toString() {
            if (actualValue != null) {
                return String.format("Line %d, Field '%s': %s (value: '%s')",
                        lineNumber, field, message, actualValue);
            }
            return String.format("Line %d, Field '%s': %s", lineNumber, field, message);
        }
    }
}

