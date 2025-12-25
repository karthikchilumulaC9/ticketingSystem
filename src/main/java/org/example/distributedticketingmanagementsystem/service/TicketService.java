package org.example.distributedticketingmanagementsystem.service;

import org.example.distributedticketingmanagementsystem.dto.BulkTicketResponse;
import org.example.distributedticketingmanagementsystem.dto.PagedResponse;
import org.example.distributedticketingmanagementsystem.dto.ServiceResponse;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.example.distributedticketingmanagementsystem.dto.TicketUpdateRequest;

import java.util.List;

/**
 * Service interface for ticket operations.
 * Defines the contract for ticket management business logic.
 *
 * <p>This interface provides two types of methods:
 * <ul>
 *   <li>Standard methods that throw exceptions on error</li>
 *   <li>Safe methods (prefixed with 'safe') that return ServiceResponse with error details</li>
 * </ul>
 */
public interface TicketService {

    // ==================== STANDARD METHODS (throw exceptions) ====================

    /**
     * Create a new ticket.
     *
     * @param request the ticket creation request
     * @return the created ticket DTO
     * @throws org.example.distributedticketingmanagementsystem.exception.NullRequestException if request is null
     * @throws org.example.distributedticketingmanagementsystem.exception.DuplicateTicketException if ticket number exists
     */
    TicketDTO createTicket(TicketCreateRequest request);

    /**
     * Get a ticket by its ID.
     *
     * @param id the ticket ID
     * @return the ticket DTO
     * @throws org.example.distributedticketingmanagementsystem.exception.TicketNotFoundException if not found
     */
    TicketDTO getTicketById(Long id);

    /**
     * Get a ticket by its ticket number.
     *
     * @param ticketNumber the ticket number
     * @return the ticket DTO
     * @throws org.example.distributedticketingmanagementsystem.exception.TicketNotFoundException if not found
     */
    TicketDTO getTicketByNumber(String ticketNumber);

    /**
     * Get all tickets.
     *
     * @return list of all ticket DTOs
     */
    List<TicketDTO> getAllTickets();

    /**
     * Get tickets by status.
     *
     * @param status the ticket status
     * @return list of ticket DTOs
     */
    List<TicketDTO> getTicketsByStatus(String status);

    /**
     * Get tickets by priority.
     *
     * @param priority the ticket priority
     * @return list of ticket DTOs
     */
    List<TicketDTO> getTicketsByPriority(String priority);

    /**
     * Get tickets by customer ID.
     *
     * @param customerId the customer ID
     * @return list of ticket DTOs
     */
    List<TicketDTO> getTicketsByCustomerId(Long customerId);

    /**
     * Get tickets by assignee.
     *
     * @param assignedTo the assignee ID
     * @return list of ticket DTOs
     */
    List<TicketDTO> getTicketsByAssignedTo(Integer assignedTo);

    /**
     * Update a ticket.
     *
     * @param id      the ticket ID
     * @param request the update request
     * @return the updated ticket DTO
     */
    TicketDTO updateTicket(Long id, TicketUpdateRequest request);

    /**
     * Delete a ticket.
     *
     * @param id the ticket ID
     */
    void deleteTicket(Long id);

    /**
     * Bulk create tickets.
     *
     * @param requests list of ticket creation requests
     * @return bulk response with success and failure details
     */
    BulkTicketResponse bulkCreateTickets(List<TicketCreateRequest> requests);

    /**
     * Get tickets with filters and pagination.
     *
     * @param status     optional status filter
     * @param priority   optional priority filter
     * @param customerId optional customer ID filter
     * @param assignedTo optional assignee filter
     * @param page       page number (0-based)
     * @param size       page size
     * @param sortBy     sort field
     * @param sortDir    sort direction (asc/desc)
     * @return paginated response of tickets
     */
    PagedResponse<TicketDTO> getTicketsWithFilters(String status, String priority,
                                                    Long customerId, Integer assignedTo,
                                                    int page, int size,
                                                    String sortBy, String sortDir);

    /**
     * Update ticket status.
     *
     * @param id     the ticket ID
     * @param status the new status
     * @return the updated ticket DTO
     */
    TicketDTO updateTicketStatus(Long id, String status);

    // ==================== SAFE METHODS (return ServiceResponse) ====================

    /**
     * Safely create a new ticket, returning errors instead of throwing exceptions.
     *
     * @param request the ticket creation request
     * @return ServiceResponse containing the created ticket or error details
     */
    ServiceResponse<TicketDTO> safeCreateTicket(TicketCreateRequest request);

    /**
     * Safely get a ticket by ID, returning errors instead of throwing exceptions.
     *
     * @param id the ticket ID
     * @return ServiceResponse containing the ticket or error details
     */
    ServiceResponse<TicketDTO> safeGetTicketById(Long id);

    /**
     * Safely get a ticket by ticket number, returning errors instead of throwing exceptions.
     *
     * @param ticketNumber the ticket number
     * @return ServiceResponse containing the ticket or error details
     */
    ServiceResponse<TicketDTO> safeGetTicketByNumber(String ticketNumber);

    /**
     * Safely update a ticket, returning errors instead of throwing exceptions.
     *
     * @param id      the ticket ID
     * @param request the update request
     * @return ServiceResponse containing the updated ticket or error details
     */
    ServiceResponse<TicketDTO> safeUpdateTicket(Long id, TicketUpdateRequest request);

    /**
     * Safely delete a ticket, returning errors instead of throwing exceptions.
     *
     * @param id the ticket ID
     * @return ServiceResponse indicating success or error details
     */
    ServiceResponse<Void> safeDeleteTicket(Long id);

    /**
     * Safely update ticket status, returning errors instead of throwing exceptions.
     *
     * @param id     the ticket ID
     * @param status the new status
     * @return ServiceResponse containing the updated ticket or error details
     */
    ServiceResponse<TicketDTO> safeUpdateTicketStatus(Long id, String status);
}
