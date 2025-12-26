package org.example.distributedticketingmanagementsystem.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.entity.Ticket;
import org.example.distributedticketingmanagementsystem.dto.BulkTicketResponse;
import org.example.distributedticketingmanagementsystem.dto.PagedResponse;
import org.example.distributedticketingmanagementsystem.dto.ServiceResponse;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.example.distributedticketingmanagementsystem.dto.TicketUpdateRequest;
import org.example.distributedticketingmanagementsystem.event.TicketCacheEvent;
import org.example.distributedticketingmanagementsystem.event.TicketCreatedEvent;
import org.example.distributedticketingmanagementsystem.event.TicketDeletedEvent;
import org.example.distributedticketingmanagementsystem.event.TicketUpdatedEvent;
import org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException;
import org.example.distributedticketingmanagementsystem.exception.InvalidTicketOperationException;
import org.example.distributedticketingmanagementsystem.exception.NullRequestException;
import org.example.distributedticketingmanagementsystem.exception.TicketNotFoundException;
import org.example.distributedticketingmanagementsystem.mapper.TicketMapper;
import org.example.distributedticketingmanagementsystem.repository.TicketRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Implementation of {@link TicketService} providing enterprise-grade ticket management.
 *
 * <h3>Architecture Design:</h3>
 * <ul>
 *   <li><strong>Event-Driven Caching:</strong> Uses Spring's ApplicationEventPublisher
 *       to publish events that are processed AFTER transaction commits</li>
 *   <li><strong>Cache Consistency:</strong> Redis cache is updated only after
 *       successful database commits, preventing stale data</li>
 *   <li><strong>Separation of Concerns:</strong> Business logic is decoupled from
 *       caching infrastructure</li>
 * </ul>
 *
 * <h3>Transaction Flow:</h3>
 * <pre>
 * 1. Service method starts transaction
 * 2. Business logic executes (validation, DB operations)
 * 3. Event is published (within transaction boundary)
 * 4. Transaction commits
 * 5. EventListener processes event (caches data in Redis)
 * </pre>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 * @see TicketCreatedEvent
 * @see TicketUpdatedEvent
 * @see TicketDeletedEvent
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TicketServiceImp implements TicketService {

    private final TicketRepository ticketRepository;
    private final TicketMapper ticketMapper;
    private final TicketValidationService validationService;
    private final TicketCacheService cacheService;

    /**
     * Event publisher for domain events.
     * Events are processed by {@link org.example.distributedticketingmanagementsystem.event.TicketEventListener}
     * AFTER transaction commits.
     */
    private final ApplicationEventPublisher eventPublisher;

    // ==================== CREATE ====================

    /**
     * Creates a new ticket with transactional safety.
     *
     * <p><strong>Transaction Design:</strong></p>
     * <ul>
     *   <li>Ticket is saved to database within transaction</li>
     *   <li>TicketCreatedEvent is published (but not processed yet)</li>
     *   <li>After transaction commits, event listener caches the ticket</li>
     *   <li>If transaction rolls back, no caching occurs</li>
     * </ul>
     *
     * <p>This ensures Redis cache only contains data that is actually
     * persisted in the database, preventing inconsistency.</p>
     *
     * @param request The ticket creation request
     * @return The created ticket DTO
     * @throws NullRequestException if request is null or has null required fields
     * @throws DuplicateTicketException if ticket number already exists
     */
    @Override
    @Transactional
    public TicketDTO createTicket(TicketCreateRequest request) {
        // Validation layer handles null checks and business rules
        validationService.validateCreateRequest(request);

        log.info("📝 Creating ticket: {}", request.getTicketNumber());

        // Business logic - save to database
        Ticket ticket = ticketMapper.toEntity(request);
        Ticket savedTicket = ticketRepository.save(ticket);

        // Map to DTO
        TicketDTO ticketDTO = ticketMapper.toDTO(savedTicket);

        // Publish event - will be processed AFTER transaction commits
        // This ensures cache is only updated for successfully persisted data
        eventPublisher.publishEvent(new TicketCreatedEvent(this, savedTicket.getId(), ticketDTO));

        log.info("✅ Ticket created - id: {}, ticketNumber: {} (cache update pending commit)",
                savedTicket.getId(), savedTicket.getTicketNumber());

        return ticketDTO;
    }


    // ==================== READ ====================

    /**
     * Retrieves a ticket by ID with cache-aside pattern.
     *
     * <p><strong>Cache Strategy:</strong></p>
     * <ol>
     *   <li>Check Redis cache first (fast path)</li>
     *   <li>On cache miss, query database</li>
     *   <li>Publish cache event to populate Redis after read completes</li>
     * </ol>
     *
     * @param id The ticket ID to retrieve
     * @return The ticket DTO
     * @throws NullRequestException if id is null or invalid
     * @throws TicketNotFoundException if ticket doesn't exist
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDTO getTicketById(Long id) {
        // Validate input
        validationService.validateId(id, "Ticket ID");

        // Check cache first (fast path)
        var cached = cacheService.getTicketById(id);
        if (cached.isPresent()) {
            log.debug("🎯 Cache HIT - Ticket ID: {}", id);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("💾 Cache MISS - Querying DB for Ticket ID: {}", id);
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        TicketDTO ticketDTO = ticketMapper.toDTO(ticket);

        // Publish cache event - will populate cache after transaction completes
        eventPublisher.publishEvent(new TicketCacheEvent(this, id, ticketDTO));

        return ticketDTO;
    }

    /**
     * Retrieves a ticket by ticket number (business key).
     *
     * <p>Uses the same cache-aside pattern as getTicketById.</p>
     *
     * @param ticketNumber The ticket number to search for
     * @return The ticket DTO
     * @throws NullRequestException if ticketNumber is null or empty
     * @throws TicketNotFoundException if ticket doesn't exist
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDTO getTicketByNumber(String ticketNumber) {
        if (!StringUtils.hasText(ticketNumber)) {
            throw new NullRequestException("ticketNumber", "Ticket number cannot be null or empty");
        }

        String trimmedNumber = ticketNumber.trim();

        // Check cache first
        var cached = cacheService.getTicketByNumber(trimmedNumber);
        if (cached.isPresent()) {
            log.debug("🎯 Cache HIT - Ticket number: {}", trimmedNumber);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("💾 Cache MISS - Querying DB for Ticket number: {}", trimmedNumber);
        Ticket ticket = ticketRepository.findByTicketNumber(trimmedNumber)
                .orElseThrow(() -> new TicketNotFoundException("ticketNumber", ticketNumber));

        TicketDTO ticketDTO = ticketMapper.toDTO(ticket);

        // Publish cache event - will populate cache after transaction completes
        eventPublisher.publishEvent(new TicketCacheEvent(this, ticket.getId(), ticketDTO));

        return ticketDTO;
    }

    /**
     * Retrieves all tickets.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketDTO> getAllTickets() {
        return ticketRepository.findAll().stream()
                .map(ticketMapper::toDTO)
                .toList();
    }

    /**
     * Retrieves tickets by status.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketDTO> getTicketsByStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return Collections.emptyList();
        }
        return ticketRepository.findByStatus(status.trim().toUpperCase()).stream()
                .map(ticketMapper::toDTO)
                .toList();
    }

    /**
     * Retrieves tickets by priority.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketDTO> getTicketsByPriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return Collections.emptyList();
        }
        return ticketRepository.findByPriority(priority.trim().toUpperCase()).stream()
                .map(ticketMapper::toDTO)
                .toList();
    }

    /**
     * Retrieves tickets by customer ID.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketDTO> getTicketsByCustomerId(Long customerId) {
        validationService.validateId(customerId, "Customer ID");
        return ticketRepository.findByCustomerId(customerId).stream()
                .map(ticketMapper::toDTO)
                .toList();
    }

    /**
     * Retrieves tickets by assignee.
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketDTO> getTicketsByAssignedTo(Integer assignedTo) {
        if (assignedTo == null || assignedTo <= 0) {
            return Collections.emptyList();
        }
        return ticketRepository.findByAssignedTo(assignedTo).stream()
                .map(ticketMapper::toDTO)
                .toList();
    }

    /**
     * Retrieves tickets with filters and pagination.
     */
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<TicketDTO> getTicketsWithFilters(
            String status, String priority, Long customerId, Integer assignedTo,
            int page, int size, String sortBy, String sortDir) {

        // Default values
        String safeSortBy = StringUtils.hasText(sortBy) ? sortBy : "createdAt";
        String safeSortDir = StringUtils.hasText(sortDir) ? sortDir : "desc";

        Sort sort = safeSortDir.equalsIgnoreCase("asc")
                ? Sort.by(safeSortBy).ascending()
                : Sort.by(safeSortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Specification<Ticket> spec = buildFilterSpecification(status, priority, customerId, assignedTo);

        Page<Ticket> ticketPage = ticketRepository.findAll(spec, pageable);

        List<TicketDTO> ticketDTOs = ticketPage.getContent().stream()
                .map(ticketMapper::toDTO)
                .toList();

        return PagedResponse.of(
                ticketDTOs,
                ticketPage.getNumber(),
                ticketPage.getSize(),
                ticketPage.getTotalElements(),
                ticketPage.getTotalPages()
        );
    }

    // ==================== UPDATE ====================

    /**
     * Updates an existing ticket with transactional safety.
     *
     * <p><strong>Transaction Design:</strong></p>
     * <ul>
     *   <li>Ticket is updated in database within transaction</li>
     *   <li>TicketUpdatedEvent is published (but not processed yet)</li>
     *   <li>After transaction commits, event listener refreshes cache</li>
     *   <li>If transaction rolls back, cache remains unchanged</li>
     * </ul>
     *
     * @param id The ticket ID to update
     * @param request The update request with new values
     * @return The updated ticket DTO
     * @throws NullRequestException if id or request is null
     * @throws TicketNotFoundException if ticket doesn't exist
     * @throws InvalidTicketOperationException if ticket cannot be updated
     */
    @Override
    @Transactional
    public TicketDTO updateTicket(Long id, TicketUpdateRequest request) {
        // Validate request
        validationService.validateUpdateRequest(id, request);

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate ticket can be updated
        validationService.validateTicketCanBeUpdated(ticket);

        // Apply updates
        applyUpdates(ticket, request);

        // Save to database
        Ticket updatedTicket = ticketRepository.save(ticket);
        TicketDTO ticketDTO = ticketMapper.toDTO(updatedTicket);

        // Publish event - will refresh cache AFTER transaction commits
        eventPublisher.publishEvent(new TicketUpdatedEvent(this, id, ticketDTO));

        log.info("✅ Ticket updated - id: {} (cache refresh pending commit)", id);
        return ticketDTO;
    }

    /**
     * Updates ticket status with state machine validation.
     *
     * <p>Validates status transitions to ensure proper workflow.</p>
     *
     * @param id The ticket ID to update
     * @param status The new status
     * @return The updated ticket DTO
     * @throws NullRequestException if id or status is null
     * @throws TicketNotFoundException if ticket doesn't exist
     * @throws InvalidTicketOperationException if status transition is invalid
     */
    @Override
    @Transactional
    public TicketDTO updateTicketStatus(Long id, String status) {
        // Validate
        validationService.validateStatusUpdateRequest(id, status);

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate transition
        String newStatus = status.trim().toUpperCase();
        validationService.validateStatusTransition(ticket.getStatus(), newStatus);

        // Update status
        String oldStatus = ticket.getStatus();
        ticket.setStatus(newStatus);

        // Set resolvedAt if resolved
        if ("RESOLVED".equals(newStatus) && ticket.getResolvedAt() == null) {
            ticket.setResolvedAt(LocalDateTime.now());
        }

        // Save to database
        Ticket updatedTicket = ticketRepository.save(ticket);
        TicketDTO ticketDTO = ticketMapper.toDTO(updatedTicket);

        // Publish event - will refresh cache AFTER transaction commits
        eventPublisher.publishEvent(new TicketUpdatedEvent(this, id, ticketDTO));

        log.info("✅ Status updated - id: {}, {} -> {} (cache refresh pending commit)",
                id, oldStatus, newStatus);
        return ticketDTO;
    }

    // ==================== DELETE ====================

    /**
     * Deletes a ticket by ID with transactional safety.
     *
     * <p><strong>Transaction Design:</strong></p>
     * <ul>
     *   <li>Ticket is deleted from database within transaction</li>
     *   <li>TicketDeletedEvent is published (but not processed yet)</li>
     *   <li>After transaction commits, event listener evicts cache</li>
     *   <li>If transaction rolls back, cache remains intact</li>
     * </ul>
     *
     * @param id The ticket ID to delete
     * @throws NullRequestException if id is null
     * @throws TicketNotFoundException if ticket doesn't exist
     * @throws InvalidTicketOperationException if ticket cannot be deleted
     */
    @Override
    @Transactional
    public void deleteTicket(Long id) {
        // Validate
        validationService.validateId(id, "Ticket ID");

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate can be deleted
        validationService.validateTicketCanBeDeleted(ticket);

        // Store ticket number for event before deletion
        String ticketNumber = ticket.getTicketNumber();

        // Delete from database
        ticketRepository.deleteById(id);

        // Publish event - will evict cache AFTER transaction commits
        eventPublisher.publishEvent(new TicketDeletedEvent(this, id, ticketNumber));

        log.info("✅ Ticket deleted - id: {} (cache eviction pending commit)", id);
    }

    // ==================== BULK OPERATIONS ====================

    /**
     * Bulk creates tickets with individual transaction isolation.
     *
     * <p>Each ticket is created in its own transaction via the
     * {@link #createTicket(TicketCreateRequest)} method. This ensures:</p>
     * <ul>
     *   <li>One failure doesn't rollback other successful creates</li>
     *   <li>Each ticket gets its own cache event after commit</li>
     *   <li>Partial success is supported</li>
     * </ul>
     *
     * @param requests List of ticket creation requests
     * @return Response with success/failure counts and details
     */
    @Override
    @Transactional
    public BulkTicketResponse bulkCreateTickets(List<TicketCreateRequest> requests) {
        BulkTicketResponse response = new BulkTicketResponse();

        if (requests == null || requests.isEmpty()) {
            log.info("📦 Bulk create called with empty request list");
            return response;
        }

        log.info("📦 Bulk creating {} tickets", requests.size());

        for (int i = 0; i < requests.size(); i++) {
            TicketCreateRequest request = requests.get(i);
            String ticketNumber = request != null ? request.getTicketNumber() : "UNKNOWN-" + (i + 1);

            try {
                TicketDTO created = createTicket(request);
                response.addSuccess(created);
            } catch (NullRequestException e) {
                response.addFailure(ticketNumber, e.getMessage(), "NULL_REQUEST");
            } catch (DuplicateTicketException e) {
                response.addFailure(ticketNumber, e.getMessage(), "DUPLICATE");
            } catch (IllegalArgumentException e) {
                response.addFailure(ticketNumber, e.getMessage(), "VALIDATION_ERROR");
            } catch (Exception e) {
                response.addFailure(ticketNumber, e.getMessage(), "ERROR");
                log.error("❌ Bulk create error for {}: {}", ticketNumber, e.getMessage());
            }
        }

        log.info("✅ Bulk create complete - success: {}, failed: {}",
                response.getSuccessCount(), response.getFailureCount());
        return response;
    }

    // ==================== SAFE METHODS (return ServiceResponse) ====================

    @Override
    public ServiceResponse<TicketDTO> safeCreateTicket(TicketCreateRequest request) {
        try {
            return ServiceResponse.success(createTicket(request));
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (DuplicateTicketException e) {
            return ServiceResponse.duplicate("Ticket", "ticketNumber",
                    request != null ? request.getTicketNumber() : "unknown");
        } catch (IllegalArgumentException e) {
            return ServiceResponse.validationError("request", e.getMessage());
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    @Override
    public ServiceResponse<TicketDTO> safeGetTicketById(Long id) {
        try {
            return ServiceResponse.success(getTicketById(id));
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (TicketNotFoundException e) {
            return ServiceResponse.notFound("Ticket", "id", id);
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    @Override
    public ServiceResponse<TicketDTO> safeGetTicketByNumber(String ticketNumber) {
        try {
            return ServiceResponse.success(getTicketByNumber(ticketNumber));
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (TicketNotFoundException e) {
            return ServiceResponse.notFound("Ticket", "ticketNumber", ticketNumber);
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    @Override
    public ServiceResponse<TicketDTO> safeUpdateTicket(Long id, TicketUpdateRequest request) {
        try {
            return ServiceResponse.success(updateTicket(id, request));
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (TicketNotFoundException e) {
            return ServiceResponse.notFound("Ticket", "id", id);
        } catch (InvalidTicketOperationException e) {
            return ServiceResponse.invalidOperation(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ServiceResponse.validationError("request", e.getMessage());
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    @Override
    public ServiceResponse<Void> safeDeleteTicket(Long id) {
        try {
            deleteTicket(id);
            return ServiceResponse.success();
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (TicketNotFoundException e) {
            return ServiceResponse.notFound("Ticket", "id", id);
        } catch (InvalidTicketOperationException e) {
            return ServiceResponse.invalidOperation(e.getMessage());
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    @Override
    public ServiceResponse<TicketDTO> safeUpdateTicketStatus(Long id, String status) {
        try {
            return ServiceResponse.success(updateTicketStatus(id, status));
        } catch (NullRequestException e) {
            return ServiceResponse.nullRequest(e.getField(), e.getMessage());
        } catch (TicketNotFoundException e) {
            return ServiceResponse.notFound("Ticket", "id", id);
        } catch (InvalidTicketOperationException e) {
            return ServiceResponse.invalidOperation(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ServiceResponse.validationError("status", e.getMessage());
        } catch (Exception e) {
            return ServiceResponse.internalError(e.getMessage());
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * Builds filter specification for dynamic queries.
     *
     * <p>Creates a JPA Specification for filtering tickets based on
     * provided criteria. All filters are combined with AND logic.</p>
     *
     * @param status Filter by ticket status
     * @param priority Filter by ticket priority
     * @param customerId Filter by customer ID
     * @param assignedTo Filter by assigned agent ID
     * @return JPA Specification for the query
     */
    private Specification<Ticket> buildFilterSpecification(
            String status, String priority, Long customerId, Integer assignedTo) {

        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.toUpperCase()));
            }
            if (StringUtils.hasText(priority)) {
                predicates.add(cb.equal(cb.upper(root.get("priority")), priority.toUpperCase()));
            }
            if (customerId != null && customerId > 0) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (assignedTo != null && assignedTo > 0) {
                predicates.add(cb.equal(root.get("assignedTo"), assignedTo));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /**
     * Applies update request fields to ticket entity.
     *
     * <p>Updates only non-null/non-empty fields from the request.
     * Validates status transitions and recalculates SLA when priority changes.</p>
     *
     * @param ticket The ticket entity to update
     * @param request The update request with new values
     */
    private void applyUpdates(Ticket ticket, TicketUpdateRequest request) {
        if (StringUtils.hasText(request.getTitle())) {
            ticket.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            ticket.setDescription(request.getDescription().trim());
        }
        if (StringUtils.hasText(request.getStatus())) {
            String newStatus = request.getStatus().toUpperCase();
            validationService.validateStatusTransition(ticket.getStatus(), newStatus);
            ticket.setStatus(newStatus);
            if ("RESOLVED".equals(newStatus) && ticket.getResolvedAt() == null) {
                ticket.setResolvedAt(LocalDateTime.now());
            }
        }
        if (StringUtils.hasText(request.getPriority())) {
            String newPriority = request.getPriority().toUpperCase();
            ticket.setPriority(newPriority);
            ticket.setSlaDueDate(validationService.calculateSlaDueDate(newPriority));
        }
        if (request.getAssignedTo() != null) {
            ticket.setAssignedTo(request.getAssignedTo());
        }
    }
}
