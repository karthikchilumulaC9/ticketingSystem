package org.example.distributedticketingmanagementsystem.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketUpdateRequest;
import org.example.distributedticketingmanagementsystem.entity.Ticket;
import org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException;
import org.example.distributedticketingmanagementsystem.exception.InvalidTicketOperationException;
import org.example.distributedticketingmanagementsystem.exception.NullRequestException;
import org.example.distributedticketingmanagementsystem.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * SINGLE SOURCE OF TRUTH for all ticket validations.
 *
 * <p>Architecture:
 * <ul>
 *   <li>Controller calls ValidationService FIRST before any business logic</li>
 *   <li>Service layer assumes input is already validated</li>
 *   <li>GlobalExceptionHandler catches all validation exceptions</li>
 * </ul>
 *
 * <p>This service handles:
 * <ul>
 *   <li>Null checks for requests and fields</li>
 *   <li>Mandatory field validation</li>
 *   <li>Ticket number uniqueness</li>
 *   <li>Status transition validation</li>
 *   <li>SLA due date calculation</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 2.0
 * @since 2024-12-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketValidationService {

    private final TicketRepository ticketRepository;

    // ==================== CONSTANTS ====================

    /** Valid ticket statuses */
    public static final Set<String> VALID_STATUSES = Set.of(
            "OPEN", "IN_PROGRESS", "PENDING", "ON_HOLD",
            "RESOLVED", "CLOSED", "REOPENED", "CANCELLED"
    );

    /** Valid ticket priorities */
    public static final Set<String> VALID_PRIORITIES = Set.of(
            "LOW", "MEDIUM", "HIGH", "CRITICAL"
    );

    /** Status transition rules: Map<CurrentStatus, Set<AllowedNextStatuses>> */
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "PENDING", "ON_HOLD", "CANCELLED", "RESOLVED"),
            "IN_PROGRESS", Set.of("PENDING", "ON_HOLD", "RESOLVED", "CANCELLED"),
            "PENDING", Set.of("IN_PROGRESS", "ON_HOLD", "RESOLVED", "CANCELLED"),
            "ON_HOLD", Set.of("IN_PROGRESS", "PENDING", "CANCELLED"),
            "RESOLVED", Set.of("CLOSED", "REOPENED"),
            "CLOSED", Set.of("REOPENED"),
            "REOPENED", Set.of("IN_PROGRESS", "PENDING", "ON_HOLD", "RESOLVED", "CANCELLED"),
            "CANCELLED", Set.of() // Cannot transition from CANCELLED
    );

    /** SLA hours by priority */
    private static final Map<String, Integer> SLA_HOURS = Map.of(
            "CRITICAL", 4,
            "HIGH", 8,
            "MEDIUM", 24,
            "LOW", 72
    );

    // Field constraints
    private static final int MAX_TICKET_NUMBER_LENGTH = 50;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 5000;

    // ==================== CREATE VALIDATION ====================

    /**
     * Validates ticket creation request - SINGLE POINT OF VALIDATION.
     * Call this method once at the beginning of createTicket flow.
     *
     * @param request the create request
     * @throws NullRequestException if request is null
     * @throws IllegalArgumentException if validation fails
     * @throws DuplicateTicketException if ticket number already exists
     */
    public void validateCreateRequest(TicketCreateRequest request) {
        log.debug("Validating create request...");

        // 1. Null request check
        if (request == null) {
            log.error("Create request is null");
            throw new NullRequestException("request", "Ticket create request cannot be null");
        }

        // 2. Collect all field validation errors
        List<String> errors = new ArrayList<>();

        // Ticket Number validation
        String ticketNumber = request.getTicketNumber();
        if (!StringUtils.hasText(ticketNumber)) {
            errors.add("Ticket number is required");
        } else {
            ticketNumber = ticketNumber.trim();
            if (ticketNumber.length() > MAX_TICKET_NUMBER_LENGTH) {
                errors.add("Ticket number must not exceed " + MAX_TICKET_NUMBER_LENGTH + " characters");
            }
        }

        // Title validation
        String title = request.getTitle();
        if (!StringUtils.hasText(title)) {
            errors.add("Title is required");
        } else if (title.trim().length() > MAX_TITLE_LENGTH) {
            errors.add("Title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }

        // Customer ID validation
        Long customerId = request.getCustomerId();
        if (customerId == null) {
            errors.add("Customer ID is required");
        } else if (customerId <= 0) {
            errors.add("Customer ID must be a positive number");
        }

        // Status validation (optional field)
        String status = request.getStatus();
        if (StringUtils.hasText(status) && !VALID_STATUSES.contains(status.toUpperCase())) {
            errors.add("Invalid status: " + status + ". Valid values: " + VALID_STATUSES);
        }

        // Priority validation (optional field)
        String priority = request.getPriority();
        if (StringUtils.hasText(priority) && !VALID_PRIORITIES.contains(priority.toUpperCase())) {
            errors.add("Invalid priority: " + priority + ". Valid values: " + VALID_PRIORITIES);
        }

        // Description length validation (optional field)
        String description = request.getDescription();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("Description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        // 3. Throw if there are validation errors
        if (!errors.isEmpty()) {
            String errorMessage = String.join("; ", errors);
            log.warn("Validation failed: {}", errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        // 4. Business rule: Ticket number uniqueness
        if (ticketRepository.existsByTicketNumber(ticketNumber)) {
            log.warn("Duplicate ticket number: {}", ticketNumber);
            throw new DuplicateTicketException(ticketNumber);
        }

        log.debug("Create request validation passed for ticket: {}", ticketNumber);
    }

    // ==================== UPDATE VALIDATION ====================

    /**
     * Validates ticket update request - SINGLE POINT OF VALIDATION.
     *
     * @param id the ticket ID
     * @param request the update request
     * @throws NullRequestException if request is null
     * @throws IllegalArgumentException if validation fails
     */
    public void validateUpdateRequest(Long id, TicketUpdateRequest request) {
        log.debug("Validating update request for ticket ID: {}", id);

        // ID validation
        validateId(id, "Ticket ID");

        // Null request check
        if (request == null) {
            log.error("Update request is null");
            throw new NullRequestException("request", "Ticket update request cannot be null");
        }

        List<String> errors = new ArrayList<>();

        // Title validation (optional but if provided, must be valid)
        String title = request.getTitle();
        if (title != null) {
            if (!StringUtils.hasText(title)) {
                errors.add("Title cannot be blank if provided");
            } else if (title.trim().length() > MAX_TITLE_LENGTH) {
                errors.add("Title must not exceed " + MAX_TITLE_LENGTH + " characters");
            }
        }

        // Status validation
        String status = request.getStatus();
        if (StringUtils.hasText(status) && !VALID_STATUSES.contains(status.toUpperCase())) {
            errors.add("Invalid status: " + status + ". Valid values: " + VALID_STATUSES);
        }

        // Priority validation
        String priority = request.getPriority();
        if (StringUtils.hasText(priority) && !VALID_PRIORITIES.contains(priority.toUpperCase())) {
            errors.add("Invalid priority: " + priority + ". Valid values: " + VALID_PRIORITIES);
        }

        // Description length validation
        String description = request.getDescription();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("Description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        if (!errors.isEmpty()) {
            String errorMessage = String.join("; ", errors);
            log.warn("Update validation failed: {}", errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        log.debug("Update request validation passed");
    }

    /**
     * Validates update request without ID (backward compatibility).
     */
    public void validateUpdateRequest(TicketUpdateRequest request) {
        validateUpdateRequest(null, request);
    }

    // ==================== STATUS UPDATE VALIDATION ====================

    /**
     * Validates status update request.
     *
     * @param id the ticket ID
     * @param status the new status
     * @throws NullRequestException if parameters are null
     * @throws IllegalArgumentException if status is invalid
     */
    public void validateStatusUpdateRequest(Long id, String status) {
        validateId(id, "Ticket ID");

        if (status == null) {
            throw new NullRequestException("status", "Status cannot be null");
        }

        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("Status cannot be empty");
        }

        if (!VALID_STATUSES.contains(status.toUpperCase())) {
            throw new IllegalArgumentException("Invalid status: " + status +
                    ". Valid values: " + VALID_STATUSES);
        }
    }

    // ==================== STATUS TRANSITION VALIDATION ====================

    /**
     * Validates status transition is allowed.
     *
     * @param currentStatus the current status
     * @param newStatus the new status
     * @throws InvalidTicketOperationException if transition is not allowed
     */
    public void validateStatusTransition(String currentStatus, String newStatus) {
        if (currentStatus == null || newStatus == null) {
            throw new IllegalArgumentException("Current status and new status cannot be null");
        }

        String current = currentStatus.toUpperCase();
        String next = newStatus.toUpperCase();

        // Same status is allowed (no-op)
        if (current.equals(next)) {
            log.debug("Status unchanged: {}", current);
            return;
        }

        // Validate new status
        if (!VALID_STATUSES.contains(next)) {
            throw new InvalidTicketOperationException("updateStatus",
                    "Invalid status: " + newStatus + ". Valid values: " + VALID_STATUSES);
        }

        // Check if transition is allowed
        Set<String> allowedTransitions = STATUS_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowedTransitions.contains(next)) {
            log.warn("Invalid status transition: {} -> {}", current, next);
            throw new InvalidTicketOperationException("updateStatus",
                    String.format("Cannot transition from '%s' to '%s'. Allowed: %s",
                            current, next, allowedTransitions.isEmpty() ? "none" : allowedTransitions));
        }

        log.debug("Status transition {} -> {} is valid", current, next);
    }

    // ==================== TICKET STATE VALIDATION ====================

    /**
     * Validates that a ticket can be updated based on its current state.
     *
     * @param ticket the ticket to validate
     * @throws InvalidTicketOperationException if ticket cannot be updated
     */
    public void validateTicketCanBeUpdated(Ticket ticket) {
        if (ticket == null) {
            throw new NullRequestException("ticket", "Ticket cannot be null");
        }

        String status = ticket.getStatus();

        if ("CLOSED".equalsIgnoreCase(status)) {
            throw new InvalidTicketOperationException("update",
                    "Ticket is closed and cannot be modified. Reopen the ticket first.");
        }

        if ("CANCELLED".equalsIgnoreCase(status)) {
            throw new InvalidTicketOperationException("update",
                    "Ticket is cancelled and cannot be modified.");
        }
    }

    /**
     * Validates that a ticket can be deleted based on its current state.
     *
     * @param ticket the ticket to validate
     * @throws InvalidTicketOperationException if ticket cannot be deleted
     */
    public void validateTicketCanBeDeleted(Ticket ticket) {
        if (ticket == null) {
            throw new NullRequestException("ticket", "Ticket cannot be null");
        }

        String status = ticket.getStatus();

        if ("RESOLVED".equalsIgnoreCase(status)) {
            throw new InvalidTicketOperationException("delete",
                    "Resolved tickets cannot be deleted for audit purposes.");
        }

        if ("CLOSED".equalsIgnoreCase(status)) {
            throw new InvalidTicketOperationException("delete",
                    "Closed tickets cannot be deleted for audit purposes.");
        }
    }

    // ==================== ID VALIDATION ====================

    /**
     * Validates ID is not null and positive.
     *
     * @param id the ID to validate
     * @param fieldName the field name for error message
     * @throws NullRequestException if ID is null
     * @throws IllegalArgumentException if ID is invalid
     */
    public void validateId(Long id, String fieldName) {
        if (id == null) {
            throw new NullRequestException(fieldName, fieldName + " cannot be null");
        }
        if (id <= 0) {
            throw new IllegalArgumentException(fieldName + " must be a positive number");
        }
    }

    // ==================== FIELD VALIDATION ====================

    /**
     * Validates priority value.
     */
    public void validatePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            throw new IllegalArgumentException("Priority cannot be null or empty");
        }
        if (!VALID_PRIORITIES.contains(priority.toUpperCase())) {
            throw new IllegalArgumentException("Invalid priority: " + priority +
                    ". Valid values: " + VALID_PRIORITIES);
        }
    }

    /**
     * Validates status value.
     */
    public void validateStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("Status cannot be null or empty");
        }
        if (!VALID_STATUSES.contains(status.toUpperCase())) {
            throw new IllegalArgumentException("Invalid status: " + status +
                    ". Valid values: " + VALID_STATUSES);
        }
    }

    // ==================== SLA UTILITIES ====================

    /**
     * Calculates SLA due date based on priority.
     */
    public LocalDateTime calculateSlaDueDate(String priority) {
        String normalizedPriority = StringUtils.hasText(priority) ? priority.toUpperCase() : "MEDIUM";
        int hours = SLA_HOURS.getOrDefault(normalizedPriority, 24);
        return LocalDateTime.now().plusHours(hours);
    }

    /**
     * Checks if a ticket is overdue based on SLA.
     */
    public boolean isTicketOverdue(Ticket ticket) {
        if (ticket == null || ticket.getSlaDueDate() == null) {
            return false;
        }

        String status = ticket.getStatus();
        if ("RESOLVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
            return false;
        }

        return LocalDateTime.now().isAfter(ticket.getSlaDueDate());
    }

    /**
     * Gets SLA hours for a priority.
     */
    public int getSlaHours(String priority) {
        return SLA_HOURS.getOrDefault(
                StringUtils.hasText(priority) ? priority.toUpperCase() : "MEDIUM",
                24
        );
    }
}
