package org.example.distributedticketingmanagementsystem.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "tickets",
        indexes = {
                @Index(name = "idx_status", columnList = "status"),
                @Index(name = "idx_priority", columnList = "priority"),
                @Index(name = "idx_created_at", columnList = "created_at"),
                @Index(name = "idx_assigned_to", columnList = "assigned_to"),
                @Index(name = "idx_customer_id", columnList = "customer_id"),
                @Index(name = "idx_sla_due_date", columnList = "sla_due_date")
        }
)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "ticket_number", length = 50, nullable = false, unique = true)
    private String ticketNumber;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", length = 50, nullable = false)
    private String status;

    @Column(name = "priority", length = 20, nullable = false)
    private String priority;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "sla_due_date")
    private LocalDateTime slaDueDate;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "assigned_to")
    private Integer assignedTo;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "OPEN";
        }
        if (this.priority == null) {
            this.priority = "MEDIUM";
        }
        // Set SLA due date based on priority
        if (this.slaDueDate == null) {
            this.slaDueDate = calculateSlaDueDate(this.priority);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Calculate SLA due date based on priority.
     * CRITICAL: 4 hours, HIGH: 8 hours, MEDIUM: 24 hours, LOW: 72 hours
     */
    private LocalDateTime calculateSlaDueDate(String priority) {
        LocalDateTime now = LocalDateTime.now();
        return switch (priority.toUpperCase()) {
            case "CRITICAL" -> now.plusHours(4);
            case "HIGH" -> now.plusHours(8);
            case "MEDIUM" -> now.plusHours(24);
            case "LOW" -> now.plusHours(72);
            default -> now.plusHours(24);
        };
    }
}
