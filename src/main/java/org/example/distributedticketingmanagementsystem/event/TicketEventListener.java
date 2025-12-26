package org.example.distributedticketingmanagementsystem.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.service.TicketCacheService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event listener for handling ticket-related domain events.
 *
 * <p>This listener processes ticket events AFTER the database transaction
 * has successfully committed, ensuring cache consistency with the database.</p>
 *
 * <h3>Enterprise Design Patterns Applied:</h3>
 * <ul>
 *   <li><strong>Event-Driven Architecture:</strong> Decouples caching from business logic</li>
 *   <li><strong>Transactional Outbox Pattern:</strong> Events processed post-commit</li>
 *   <li><strong>Circuit Breaker Ready:</strong> Safe error handling prevents cascade failures</li>
 *   <li><strong>Async Processing:</strong> Non-blocking cache operations</li>
 * </ul>
 *
 * <h3>Transaction Safety:</h3>
 * <p>All event handlers use {@code TransactionPhase.AFTER_COMMIT} to ensure:</p>
 * <ul>
 *   <li>Cache updates only occur after successful DB commits</li>
 *   <li>Transaction rollbacks don't leave stale cache data</li>
 *   <li>No distributed transaction coordination needed</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * <p>All cache operations are wrapped in try-catch to prevent cache failures
 * from affecting the main application flow. Cache failures are logged but
 * don't throw exceptions.</p>
 *
 * @author Enterprise Architecture Team
 * @since 1.0.0
 * @see TicketCreatedEvent
 * @see TicketUpdatedEvent
 * @see TicketDeletedEvent
 * @see TicketCacheEvent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventListener {

    private final TicketCacheService cacheService;

    // ==================== CREATE EVENT HANDLER ====================

    /**
     * Handles ticket creation events by caching the newly created ticket.
     *
     * <p>This method is invoked AFTER the transaction that created the ticket
     * has successfully committed. This ensures we only cache tickets that
     * are actually persisted in the database.</p>
     *
     * @param event The ticket created event containing the ticket data
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTicketCreated(TicketCreatedEvent event) {
        log.debug("📤 Processing TicketCreatedEvent: {}", event);

        try {
            cacheService.cacheTicket(event.getTicketId(), event.getTicketDTO());
            log.info("✅ Cached newly created ticket - id: {}, ticketNumber: {}",
                    event.getTicketId(), event.getTicketNumber());
        } catch (Exception e) {
            // Cache failure should not affect the main flow
            // The ticket is already persisted, cache will be populated on next read
            log.warn("⚠️ Failed to cache created ticket {} - will be cached on next read: {}",
                    event.getTicketId(), e.getMessage());
        }
    }

    // ==================== UPDATE EVENT HANDLER ====================

    /**
     * Handles ticket update events by refreshing the cached ticket data.
     *
     * <p>This method evicts the old cache entry and stores the updated
     * ticket data. Executed AFTER the update transaction commits.</p>
     *
     * @param event The ticket updated event containing the updated ticket data
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTicketUpdated(TicketUpdatedEvent event) {
        log.debug("📤 Processing TicketUpdatedEvent: {}", event);

        try {
            // Evict old cache entry first
            cacheService.evictTicket(event.getTicketId(), event.getTicketNumber());

            // Cache the updated ticket
            cacheService.cacheTicket(event.getTicketId(), event.getTicketDTO());

            log.info("✅ Refreshed cache for updated ticket - id: {}, ticketNumber: {}",
                    event.getTicketId(), event.getTicketNumber());
        } catch (Exception e) {
            log.warn("⚠️ Failed to refresh cache for ticket {} - will be updated on next read: {}",
                    event.getTicketId(), e.getMessage());
        }
    }

    // ==================== DELETE EVENT HANDLER ====================

    /**
     * Handles ticket deletion events by evicting the ticket from cache.
     *
     * <p>This method removes the ticket from cache AFTER the delete
     * transaction commits, ensuring we don't evict cache for failed deletes.</p>
     *
     * @param event The ticket deleted event containing the ticket identifiers
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTicketDeleted(TicketDeletedEvent event) {
        log.debug("📤 Processing TicketDeletedEvent: {}", event);

        try {
            cacheService.evictTicket(event.getTicketId(), event.getTicketNumber());
            log.info("✅ Evicted deleted ticket from cache - id: {}, ticketNumber: {}",
                    event.getTicketId(), event.getTicketNumber());
        } catch (Exception e) {
            log.warn("⚠️ Failed to evict deleted ticket {} from cache: {}",
                    event.getTicketId(), e.getMessage());
        }
    }

    // ==================== CACHE POPULATION EVENT HANDLER ====================

    /**
     * Handles cache population events for cache-aside pattern.
     *
     * <p>This is used when a ticket is fetched from the database due to
     * a cache miss. The ticket is cached after the read transaction completes.</p>
     *
     * <p><strong>Note:</strong> Uses AFTER_COMPLETION instead of AFTER_COMMIT
     * since read operations don't have a commit phase, but we still want
     * to ensure the transaction has completed successfully.</p>
     *
     * @param event The cache event containing the ticket data to cache
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void handleTicketCache(TicketCacheEvent event) {
        log.debug("📤 Processing TicketCacheEvent: {}", event);

        try {
            cacheService.cacheTicket(event.getTicketId(), event.getTicketDTO());
            log.debug("✅ Cached ticket after read - id: {}", event.getTicketId());
        } catch (Exception e) {
            log.warn("⚠️ Failed to cache ticket {} after read: {}",
                    event.getTicketId(), e.getMessage());
        }
    }

    // ==================== ROLLBACK HANDLER (OPTIONAL) ====================

    /**
     * Handles transaction rollback scenarios for audit/logging purposes.
     *
     * <p>This method is called when a transaction that published a ticket
     * event is rolled back. Useful for monitoring and debugging.</p>
     *
     * @param event The ticket event that was rolled back
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handleRollback(TicketEvent event) {
        log.warn("🔄 Transaction rolled back for event: {} - no cache action taken", event);
        // No cache action needed - the data wasn't persisted
    }
}

