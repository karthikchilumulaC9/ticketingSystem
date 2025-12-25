package org.example.distributedticketingmanagementsystem.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.entity.Ticket;
import org.example.distributedticketingmanagementsystem.repository.TicketRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * DataLoader component that loads dummy data for testing purposes.
 * Only active when 'dev' or 'test' profile is active.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile({"dev", "test", "default"})
public class DataLoader implements CommandLineRunner {

    private final TicketRepository ticketRepository;

    @Override
    public void run(String... args) {
        if (ticketRepository.count() == 0) {
            log.info("Loading dummy ticket data...");
            loadDummyData();
            log.info("Dummy data loaded successfully. Total tickets: {}", ticketRepository.count());
        } else {
            log.info("Database already contains {} tickets. Skipping data loading.", ticketRepository.count());
        }
    }

    private void loadDummyData() {
        List<Ticket> tickets = Arrays.asList(
                Ticket.builder()
                        .ticketNumber("TKT-2024-001")
                        .title("Login Authentication Issue")
                        .description("Users are unable to login to the system. Getting 401 Unauthorized error consistently.")
                        .status("OPEN")
                        .priority("HIGH")
                        .customerId(1001L)
                        .assignedTo(101)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-002")
                        .title("Dashboard Loading Slowly")
                        .description("The main dashboard takes more than 10 seconds to load. Performance optimization needed.")
                        .status("IN_PROGRESS")
                        .priority("MEDIUM")
                        .customerId(1002L)
                        .assignedTo(102)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-003")
                        .title("Email Notifications Not Sending")
                        .description("Automated email notifications are not being sent to customers after ticket creation.")
                        .status("OPEN")
                        .priority("HIGH")
                        .customerId(1003L)
                        .assignedTo(101)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-004")
                        .title("UI Button Alignment Issue")
                        .description("Submit button on the ticket form is misaligned on mobile devices.")
                        .status("RESOLVED")
                        .priority("LOW")
                        .customerId(1001L)
                        .assignedTo(103)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-005")
                        .title("Database Connection Timeout")
                        .description("Intermittent database connection timeouts during peak hours causing service disruption.")
                        .status("IN_PROGRESS")
                        .priority("CRITICAL")
                        .customerId(1004L)
                        .assignedTo(104)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-006")
                        .title("Report Generation Failed")
                        .description("Monthly sales report generation fails with out of memory error for large datasets.")
                        .status("OPEN")
                        .priority("MEDIUM")
                        .customerId(1005L)
                        .assignedTo(102)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-007")
                        .title("Password Reset Email Not Received")
                        .description("Customer reports not receiving password reset email. Checked spam folder as well.")
                        .status("PENDING")
                        .priority("HIGH")
                        .customerId(1006L)
                        .assignedTo(null)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-008")
                        .title("API Rate Limiting Issue")
                        .description("External API integration hitting rate limits too frequently. Need to implement caching.")
                        .status("IN_PROGRESS")
                        .priority("MEDIUM")
                        .customerId(1007L)
                        .assignedTo(105)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-009")
                        .title("Data Export Feature Request")
                        .description("Customer requesting ability to export ticket data to CSV format.")
                        .status("OPEN")
                        .priority("LOW")
                        .customerId(1002L)
                        .assignedTo(null)
                        .build(),

                Ticket.builder()
                        .ticketNumber("TKT-2024-010")
                        .title("Security Vulnerability Patch")
                        .description("Critical security vulnerability identified in authentication module. Immediate patching required.")
                        .status("IN_PROGRESS")
                        .priority("CRITICAL")
                        .customerId(1001L)
                        .assignedTo(104)
                        .build()
        );

        ticketRepository.saveAll(tickets);
    }
}

