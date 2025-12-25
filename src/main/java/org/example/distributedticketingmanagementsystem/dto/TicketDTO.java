package org.example.distributedticketingmanagementsystem.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Ticket.
 * Implements Serializable for Redis caching with JDK serialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String ticketNumber;
    private String title;
    private String description;
    private String status;
    private String priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime slaDueDate;
    private LocalDateTime resolvedAt;
    private Long customerId;
    private Integer assignedTo;
    private boolean overdue;
}

