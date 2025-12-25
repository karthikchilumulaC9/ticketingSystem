package org.example.distributedticketingmanagementsystem.mapper;

import org.example.distributedticketingmanagementsystem.entity.Ticket;
import org.example.distributedticketingmanagementsystem.dto.TicketCreateRequest;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TicketMapper {

    public TicketDTO toDTO(Ticket ticket) {
        if (ticket == null) {
            return null;
        }

        boolean isOverdue = calculateOverdue(ticket);

        return TicketDTO.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .status(ticket.getStatus())
                .priority(ticket.getPriority())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .slaDueDate(ticket.getSlaDueDate())
                .resolvedAt(ticket.getResolvedAt())
                .customerId(ticket.getCustomerId())
                .assignedTo(ticket.getAssignedTo())
                .overdue(isOverdue)
                .build();
    }

    public Ticket toEntity(TicketCreateRequest request) {
        if (request == null) {
            return null;
        }
        return Ticket.builder()
                .ticketNumber(request.getTicketNumber())
                .title(request.getTitle())
                .description(request.getDescription())
                .status(request.getStatus() != null ? request.getStatus().toUpperCase() : "OPEN")
                .priority(request.getPriority() != null ? request.getPriority().toUpperCase() : "MEDIUM")
                .customerId(request.getCustomerId())
                .assignedTo(request.getAssignedTo())
                .build();
    }

    /**
     * Calculate if a ticket is overdue based on SLA due date and status.
     */
    private boolean calculateOverdue(Ticket ticket) {
        if (ticket.getSlaDueDate() == null) {
            return false;
        }

        String status = ticket.getStatus();
        // Resolved or closed tickets are not considered overdue
        if ("RESOLVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
            return false;
        }

        return LocalDateTime.now().isAfter(ticket.getSlaDueDate());
    }
}

