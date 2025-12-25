package org.example.distributedticketingmanagementsystem.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.example.distributedticketingmanagementsystem.dto.TicketUpdateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketStatusUpdateRequest;
import org.example.distributedticketingmanagementsystem.dto.BulkTicketResponse;
import org.example.distributedticketingmanagementsystem.dto.PagedResponse;
import org.example.distributedticketingmanagementsystem.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    // ==================== CREATE ====================

    /**
     * Create a new ticket.
     * POST /api/tickets
     */
    @PostMapping
    public ResponseEntity<TicketDTO> createTicket(@RequestBody TicketCreateRequest request) {
        log.info("POST /api/tickets - Creating ticket");
        TicketDTO createdTicket = ticketService.createTicket(request);
        return new ResponseEntity<>(createdTicket, HttpStatus.CREATED);
    }

    // ==================== READ ====================

    /**
     * Get a ticket by ID.
     * GET /api/tickets/{id}
     */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<TicketDTO> getTicketById(@PathVariable Long id) {
        log.debug("GET /api/tickets/{}", id);
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    /**
     * Get a ticket by ticket number.
     * GET /api/tickets/number/{ticketNumber}
     */
    @GetMapping("/number/{ticketNumber}")
    public ResponseEntity<TicketDTO> getTicketByNumber(@PathVariable String ticketNumber) {
        log.debug("GET /api/tickets/number/{}", ticketNumber);
        return ResponseEntity.ok(ticketService.getTicketByNumber(ticketNumber));
    }

    /**
     * Get all tickets.
     * GET /api/tickets
     */
    @GetMapping
    public ResponseEntity<List<TicketDTO>> getAllTickets() {
        log.debug("GET /api/tickets - Fetching all tickets");
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    /**
     * Get tickets by status.
     * GET /api/tickets/status/{status}
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<TicketDTO>> getTicketsByStatus(@PathVariable String status) {
        log.debug("GET /api/tickets/status/{}", status);
        return ResponseEntity.ok(ticketService.getTicketsByStatus(status));
    }

    /**
     * Get tickets by priority.
     * GET /api/tickets/priority/{priority}
     */
    @GetMapping("/priority/{priority}")
    public ResponseEntity<List<TicketDTO>> getTicketsByPriority(@PathVariable String priority) {
        log.debug("GET /api/tickets/priority/{}", priority);
        return ResponseEntity.ok(ticketService.getTicketsByPriority(priority));
    }

    /**
     * Get tickets by customer ID.
     * GET /api/tickets/customer/{customerId}
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<TicketDTO>> getTicketsByCustomerId(@PathVariable Long customerId) {
        log.debug("GET /api/tickets/customer/{}", customerId);
        return ResponseEntity.ok(ticketService.getTicketsByCustomerId(customerId));
    }

    /**
     * Get tickets with filters and pagination.
     * GET /api/tickets/filter
     */
    @GetMapping("/filter")
    public ResponseEntity<PagedResponse<TicketDTO>> getTicketsWithFilters(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Integer assignedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        log.debug("GET /api/tickets/filter - page: {}, size: {}", page, size);
        return ResponseEntity.ok(ticketService.getTicketsWithFilters(
                status, priority, customerId, assignedTo, page, size, sortBy, sortDir));
    }

    // ==================== UPDATE ====================

    /**
     * Update a ticket.
     * PUT /api/tickets/{id}
     */
    @PutMapping("/{id:\\d+}")
    public ResponseEntity<TicketDTO> updateTicket(
            @PathVariable Long id,
            @RequestBody TicketUpdateRequest request) {
        log.info("PUT /api/tickets/{}", id);
        return ResponseEntity.ok(ticketService.updateTicket(id, request));
    }

    /**
     * Update ticket status.
     * PATCH /api/tickets/{id}/status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketDTO> updateTicketStatus(
            @PathVariable Long id,
            @RequestBody TicketStatusUpdateRequest request) {
        log.info("PATCH /api/tickets/{}/status", id);
        return ResponseEntity.ok(ticketService.updateTicketStatus(id, request.getStatus()));
    }

    // ==================== DELETE ====================

    /**
     * Delete a ticket.
     * DELETE /api/tickets/{id}
     */
    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<Void> deleteTicket(@PathVariable Long id) {
        log.info("DELETE /api/tickets/{}", id);
        ticketService.deleteTicket(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== BULK OPERATIONS ====================

    /**
     * Bulk create tickets.
     * POST /api/tickets/bulk
     */
    @PostMapping("/bulk")
    public ResponseEntity<BulkTicketResponse> bulkCreateTickets(
            @RequestBody List<TicketCreateRequest> requests) {
        log.info("POST /api/tickets/bulk - {} tickets", requests != null ? requests.size() : 0);
        return new ResponseEntity<>(ticketService.bulkCreateTickets(requests), HttpStatus.CREATED);
    }
}

