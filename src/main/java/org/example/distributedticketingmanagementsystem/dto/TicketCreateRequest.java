package org.example.distributedticketingmanagementsystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketCreateRequest {

    @NotBlank(message = "Ticket number is required")
    @Size(max = 50, message = "Ticket number must not exceed 50 characters")
    private String ticketNumber;

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @Size(max = 50, message = "Status must not exceed 50 characters")
    private String status;

    @Size(max = 20, message = "Priority must not exceed 20 characters")
    private String priority;

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    private Integer assignedTo;
}

