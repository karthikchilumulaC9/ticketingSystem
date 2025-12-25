package org.example.distributedticketingmanagementsystem.repository;

import org.example.distributedticketingmanagementsystem.entity.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Ticket entity.
 * Extends JpaRepository for basic CRUD and JpaSpecificationExecutor for dynamic queries.
 *
 * DAO Operations:
 * - Create: saveTicket(Ticket ticket) - inherited from JpaRepository.save()
 * - Read: getTicketById(Long id), getTicketsByStatus()
 * - Update: updateTicket(Ticket ticket) - inherited from JpaRepository.save()
 * - Delete: deleteTicket(Long id) - inherited from JpaRepository.deleteById()
 */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    // ==================== READ OPERATIONS ====================

    /**
     * Find ticket by ticket number (unique identifier).
     */
    Optional<Ticket> findByTicketNumber(String ticketNumber);

    /**
     * Find all tickets by status.
     */
    List<Ticket> findByStatus(String status);

    /**
     * Find all tickets by status with pagination.
     */
    Page<Ticket> findByStatus(String status, Pageable pageable);

    /**
     * Find all tickets by priority.
     */
    List<Ticket> findByPriority(String priority);

    /**
     * Find all tickets by priority with pagination.
     */
    Page<Ticket> findByPriority(String priority, Pageable pageable);

    /**
     * Find all tickets by customer ID.
     */
    List<Ticket> findByCustomerId(Long customerId);

    /**
     * Find all tickets by assigned user.
     */
    List<Ticket> findByAssignedTo(Integer assignedTo);

    /**
     * Find all tickets by status and priority.
     */
    List<Ticket> findByStatusAndPriority(String status, String priority);

    /**
     * Check if ticket number already exists.
     */
    boolean existsByTicketNumber(String ticketNumber);

    // ==================== CUSTOM QUERIES ====================

    /**
     * Find tickets by status ordered by created date.
     */
    @Query("SELECT t FROM Ticket t WHERE UPPER(t.status) = UPPER(:status) ORDER BY t.createdAt DESC")
    List<Ticket> getTicketsByStatus(@Param("status") String status);

    /**
     * Find tickets by status with pagination.
     */
    @Query("SELECT t FROM Ticket t WHERE UPPER(t.status) = UPPER(:status)")
    Page<Ticket> getTicketsByStatusPaged(@Param("status") String status, Pageable pageable);

    /**
     * Find all open tickets (not CLOSED or RESOLVED).
     */
    @Query("SELECT t FROM Ticket t WHERE UPPER(t.status) NOT IN ('CLOSED', 'RESOLVED') ORDER BY t.priority DESC, t.createdAt ASC")
    List<Ticket> findAllOpenTickets();

    /**
     * Find tickets that are overdue (past SLA due date).
     */
    @Query("SELECT t FROM Ticket t WHERE t.slaDueDate IS NOT NULL AND t.slaDueDate < :currentTime AND UPPER(t.status) NOT IN ('CLOSED', 'RESOLVED')")
    List<Ticket> findOverdueTickets(@Param("currentTime") LocalDateTime currentTime);

    /**
     * Find tickets approaching SLA deadline (within specified hours).
     */
    @Query("SELECT t FROM Ticket t WHERE t.slaDueDate IS NOT NULL AND t.slaDueDate BETWEEN :currentTime AND :deadlineTime AND UPPER(t.status) NOT IN ('CLOSED', 'RESOLVED')")
    List<Ticket> findTicketsApproachingSla(@Param("currentTime") LocalDateTime currentTime, @Param("deadlineTime") LocalDateTime deadlineTime);

    /**
     * Count tickets by status.
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE UPPER(t.status) = UPPER(:status)")
    Long countByStatus(@Param("status") String status);

    /**
     * Find unassigned tickets.
     */
    @Query("SELECT t FROM Ticket t WHERE t.assignedTo IS NULL AND UPPER(t.status) NOT IN ('CLOSED', 'RESOLVED') ORDER BY t.priority DESC, t.createdAt ASC")
    List<Ticket> findUnassignedTickets();

    // ==================== UPDATE OPERATIONS ====================

    /**
     * Update ticket status.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id = :id")
    int updateTicketStatus(@Param("id") Long id, @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Assign ticket to user.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.assignedTo = :assignedTo, t.updatedAt = :updatedAt WHERE t.id = :id")
    int assignTicket(@Param("id") Long id, @Param("assignedTo") Integer assignedTo, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Update ticket priority.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.priority = :priority, t.updatedAt = :updatedAt WHERE t.id = :id")
    int updateTicketPriority(@Param("id") Long id, @Param("priority") String priority, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * Bulk update ticket status.
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id IN :ids")
    int bulkUpdateStatus(@Param("ids") List<Long> ids, @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);

    // ==================== DELETE OPERATIONS ====================

    /**
     * Delete ticket by ticket number.
     */
    @Modifying
    @Query("DELETE FROM Ticket t WHERE t.ticketNumber = :ticketNumber")
    int deleteByTicketNumber(@Param("ticketNumber") String ticketNumber);

    /**
     * Delete all tickets by customer ID.
     */
    @Modifying
    @Query("DELETE FROM Ticket t WHERE t.customerId = :customerId")
    int deleteByCustomerId(@Param("customerId") Long customerId);
}
