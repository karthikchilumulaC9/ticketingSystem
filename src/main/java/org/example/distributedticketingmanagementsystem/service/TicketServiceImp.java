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
import org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException;
import org.example.distributedticketingmanagementsystem.exception.InvalidTicketOperationException;
import org.example.distributedticketingmanagementsystem.exception.NullRequestException;
import org.example.distributedticketingmanagementsystem.exception.TicketNotFoundException;
import org.example.distributedticketingmanagementsystem.mapper.TicketMapper;
import org.example.distributedticketingmanagementsystem.repository.TicketRepository;
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


@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TicketServiceImp implements TicketService {

    private final TicketRepository ticketRepository;
    private final TicketMapper ticketMapper;
    private final TicketValidationService validationService;
    private final TicketCacheService cacheService;

    // ==================== CREATE ====================

    /**
     * Creates a new ticket.
     * Validation is done by ValidationService - this method handles business logic only.
     */
    @Override
    public TicketDTO createTicket(TicketCreateRequest request) {
        // Step 1: Validate (throws exception if invalid)
        validationService.validateCreateRequest(request);

        log.info("Creating ticket: {}", request.getTicketNumber());

        // Step 2: Map to entity
        Ticket ticket = ticketMapper.toEntity(request);

        // Step 3: Save to database
        Ticket savedTicket = ticketRepository.save(ticket);

        // Step 4: Map to DTO
        TicketDTO ticketDTO = ticketMapper.toDTO(savedTicket);

        // Step 5: Cache the new ticket
        safelyCacheTicket(savedTicket.getId(), ticketDTO);

        log.info("✅ Ticket created - id: {}, ticketNumber: {}",
                savedTicket.getId(), savedTicket.getTicketNumber());

        return ticketDTO;
    }

    // ==================== READ ====================

    /**
     * Retrieves a ticket by ID with cache-aside pattern.
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDTO getTicketById(Long id) {
        // Validate
        validationService.validateId(id, "Ticket ID");

        // Check cache first
        var cached = cacheService.getTicketById(id);
        if (cached.isPresent()) {
            log.debug("Cache HIT - Ticket ID: {}", id);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("Cache MISS - Querying DB for Ticket ID: {}", id);
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        TicketDTO ticketDTO = ticketMapper.toDTO(ticket);

        // Cache the result
        safelyCacheTicket(id, ticketDTO);

        return ticketDTO;
    }

    /**
     * Retrieves a ticket by ticket number.
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDTO getTicketByNumber(String ticketNumber) {
        if (!StringUtils.hasText(ticketNumber)) {
            throw new NullRequestException("ticketNumber", "Ticket number cannot be null or empty");
        }

        String trimmedNumber = ticketNumber.trim();

        // Check cache
        var cached = cacheService.getTicketByNumber(trimmedNumber);
        if (cached.isPresent()) {
            log.debug("Cache HIT - Ticket number: {}", trimmedNumber);
            return cached.get();
        }

        // Query database
        Ticket ticket = ticketRepository.findByTicketNumber(trimmedNumber)
                .orElseThrow(() -> new TicketNotFoundException("ticketNumber", ticketNumber));

        TicketDTO ticketDTO = ticketMapper.toDTO(ticket);
        safelyCacheTicket(ticket.getId(), ticketDTO);

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
     * Updates an existing ticket.
     */
    @Override
    public TicketDTO updateTicket(Long id, TicketUpdateRequest request) {
        // Validate request
        validationService.validateUpdateRequest(id, request);

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate ticket can be updated
        validationService.validateTicketCanBeUpdated(ticket);

        // Evict cache
        safelyEvictCache(id, ticket.getTicketNumber());

        // Apply updates
        applyUpdates(ticket, request);

        // Save
        Ticket updatedTicket = ticketRepository.save(ticket);
        TicketDTO ticketDTO = ticketMapper.toDTO(updatedTicket);

        // Re-cache
        safelyCacheTicket(id, ticketDTO);

        log.info("✅ Ticket updated - id: {}", id);
        return ticketDTO;
    }

    /**
     * Updates ticket status.
     */
    @Override
    public TicketDTO updateTicketStatus(Long id, String status) {
        // Validate
        validationService.validateStatusUpdateRequest(id, status);

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate transition
        String newStatus = status.trim().toUpperCase();
        validationService.validateStatusTransition(ticket.getStatus(), newStatus);

        // Evict cache
        safelyEvictCache(id, ticket.getTicketNumber());

        // Update status
        String oldStatus = ticket.getStatus();
        ticket.setStatus(newStatus);

        // Set resolvedAt if resolved
        if ("RESOLVED".equals(newStatus) && ticket.getResolvedAt() == null) {
            ticket.setResolvedAt(LocalDateTime.now());
        }

        // Save
        Ticket updatedTicket = ticketRepository.save(ticket);
        TicketDTO ticketDTO = ticketMapper.toDTO(updatedTicket);

        // Re-cache
        safelyCacheTicket(id, ticketDTO);

        log.info("✅ Status updated - id: {}, {} -> {}", id, oldStatus, newStatus);
        return ticketDTO;
    }

    // ==================== DELETE ====================

    /**
     * Deletes a ticket by ID.
     */
    @Override
    public void deleteTicket(Long id) {
        // Validate
        validationService.validateId(id, "Ticket ID");

        // Fetch ticket
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));

        // Validate can be deleted
        validationService.validateTicketCanBeDeleted(ticket);

        // Evict cache
        safelyEvictCache(id, ticket.getTicketNumber());

        // Delete
        ticketRepository.deleteById(id);

        log.info("✅ Ticket deleted - id: {}", id);
    }

    // ==================== BULK OPERATIONS ====================

    /**
     * Bulk creates tickets.
     */
    @Override
    public BulkTicketResponse bulkCreateTickets(List<TicketCreateRequest> requests) {
        BulkTicketResponse response = new BulkTicketResponse();

        if (requests == null || requests.isEmpty()) {
            return response;
        }

        log.info("Bulk creating {} tickets", requests.size());

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
                log.error("Bulk create error for {}: {}", ticketNumber, e.getMessage());
            }
        }

        log.info("Bulk create complete - success: {}, failed: {}",
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
     * Safely caches a ticket (cache failure doesn't fail operation).
     */
    private void safelyCacheTicket(Long id, TicketDTO ticketDTO) {
        try {
            cacheService.cacheTicket(id, ticketDTO);
        } catch (Exception e) {
            log.warn("Cache write failed for ticket {}: {}", id, e.getMessage());
        }
    }

    /**
     * Safely evicts cache (cache failure doesn't fail operation).
     */
    private void safelyEvictCache(Long id, String ticketNumber) {
        try {
            cacheService.evictTicket(id, ticketNumber);
        } catch (Exception e) {
            log.warn("Cache evict failed for ticket {}: {}", id, e.getMessage());
        }
    }

    /**
     * Builds filter specification for dynamic queries.
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
