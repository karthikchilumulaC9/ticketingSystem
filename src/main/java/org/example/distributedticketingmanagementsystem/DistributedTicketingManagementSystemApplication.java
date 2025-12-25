package org.example.distributedticketingmanagementsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Distributed Ticketing Management System.
 *
 * <p>This application provides enterprise-level ticket management with:
 * <ul>
 *     <li>RESTful API for ticket CRUD operations</li>
 *     <li>Redis caching for improved performance</li>
 *     <li>Kafka-based bulk processing for high-volume ticket creation</li>
 *     <li>Asynchronous processing for non-blocking operations</li>
 *     <li>Scheduled tasks for DLQ monitoring and reprocessing</li>
 * </ul>
 *
 * @author Ticketing System Team
 * @version 1.0
 * @since 2024
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class DistributedTicketingManagementSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributedTicketingManagementSystemApplication.class, args);
    }

}
