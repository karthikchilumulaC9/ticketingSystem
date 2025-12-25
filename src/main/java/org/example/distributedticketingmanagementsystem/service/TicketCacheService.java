package org.example.distributedticketingmanagementsystem.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.dto.TicketDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing ticket cache operations in Redis.
 *
 * Caching Strategy:
 * - Key format: "ticket:{id}" for individual tickets
 * - Key format: "ticket:number:{ticketNumber}" for lookup by ticket number
 * - TTL: 30 minutes (configurable)
 * - Serialization: JSON format
 *
 * Cache Invalidation Triggers:
 * - Ticket creation: N/A (nothing to invalidate)
 * - Ticket update: Delete cache entry
 * - Ticket deletion: Delete cache entry
 * - Bulk operations: Pattern-based invalidation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String TICKET_CACHE_PREFIX = "ticket:";
    private static final String TICKET_NUMBER_PREFIX = "ticket:number:";
    private static final long DEFAULT_TTL_MINUTES = 30;

    // ==================== GET Operations ====================

    /**
     * Get ticket from cache by ID.
     *
     * @param id the ticket ID
     * @return Optional containing the cached ticket, or empty if not found
     */
    public Optional<TicketDTO> getTicketById(Long id) {
        String key = buildTicketKey(id);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.info("🟢 REDIS CACHE HIT - Ticket ID: {} retrieved from Redis cache", id);
                return Optional.of((TicketDTO) cached);
            }
            log.info("🔴 REDIS CACHE MISS - Ticket ID: {} not found in cache, will query database", id);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("⚠️ REDIS ERROR - Error retrieving ticket from cache for ID {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get ticket from cache by ticket number.
     *
     * @param ticketNumber the ticket number
     * @return Optional containing the cached ticket, or empty if not found
     */
    public Optional<TicketDTO> getTicketByNumber(String ticketNumber) {
        String key = buildTicketNumberKey(ticketNumber);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Cache HIT for ticket number: {}", ticketNumber);
                return Optional.of((TicketDTO) cached);
            }
            log.debug("Cache MISS for ticket number: {}", ticketNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error retrieving ticket from cache for number {}: {}", ticketNumber, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== PUT Operations ====================

    /**
     * Cache a ticket by ID.
     *
     * @param id the ticket ID
     * @param ticket the ticket DTO to cache
     */
    public void cacheTicket(Long id, TicketDTO ticket) {
        cacheTicket(id, ticket, DEFAULT_TTL_MINUTES);
    }

    /**
     * Cache a ticket by ID with custom TTL.
     *
     * @param id the ticket ID
     * @param ticket the ticket DTO to cache
     * @param ttlMinutes the TTL in minutes
     */
    public void cacheTicket(Long id, TicketDTO ticket, long ttlMinutes) {
        String key = buildTicketKey(id);
        try {
            redisTemplate.opsForValue().set(key, ticket, ttlMinutes, TimeUnit.MINUTES);
            log.info("💾 REDIS CACHE STORE - Ticket ID: {} cached successfully (TTL: {} minutes)", id, ttlMinutes);

            // Also cache by ticket number for quick lookup
            if (ticket.getTicketNumber() != null) {
                cacheTicketByNumber(ticket.getTicketNumber(), ticket, ttlMinutes);
            }
        } catch (Exception e) {
            log.warn("⚠️ REDIS ERROR - Error caching ticket with ID {}: {}", id, e.getMessage());
        }
    }

    /**
     * Cache a ticket by ticket number.
     *
     * @param ticketNumber the ticket number
     * @param ticket the ticket DTO to cache
     * @param ttlMinutes the TTL in minutes
     */
    public void cacheTicketByNumber(String ticketNumber, TicketDTO ticket, long ttlMinutes) {
        String key = buildTicketNumberKey(ticketNumber);
        try {
            redisTemplate.opsForValue().set(key, ticket, ttlMinutes, TimeUnit.MINUTES);
            log.debug("Cached ticket with number: {} (TTL: {} minutes)", ticketNumber, ttlMinutes);
        } catch (Exception e) {
            log.warn("Error caching ticket with number {}: {}", ticketNumber, e.getMessage());
        }
    }

    // ==================== EVICT Operations ====================

    /**
     * Evict ticket from cache by ID.
     * Called on ticket update or deletion.
     *
     * @param id the ticket ID
     */
    public void evictTicketById(Long id) {
        String key = buildTicketKey(id);
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Evicted ticket cache for ID: {}", id);
            }
        } catch (Exception e) {
            log.warn("Error evicting ticket cache for ID {}: {}", id, e.getMessage());
        }
    }

    /**
     * Evict ticket from cache by ticket number.
     *
     * @param ticketNumber the ticket number
     */
    public void evictTicketByNumber(String ticketNumber) {
        String key = buildTicketNumberKey(ticketNumber);
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Evicted ticket cache for number: {}", ticketNumber);
            }
        } catch (Exception e) {
            log.warn("Error evicting ticket cache for number {}: {}", ticketNumber, e.getMessage());
        }
    }

    /**
     * Evict ticket from cache by ID and ticket number.
     * Called on ticket update or deletion to ensure both cache entries are removed.
     *
     * @param id the ticket ID
     * @param ticketNumber the ticket number
     */
    public void evictTicket(Long id, String ticketNumber) {
        evictTicketById(id);
        if (ticketNumber != null) {
            evictTicketByNumber(ticketNumber);
        }
    }

    /**
     * Evict multiple tickets from cache.
     * Called on bulk operations.
     *
     * @param ids the list of ticket IDs to evict
     */
    public void evictTickets(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        try {
            List<String> keys = ids.stream()
                    .map(this::buildTicketKey)
                    .toList();
            Long deletedCount = redisTemplate.delete(keys);
            log.debug("Evicted {} ticket cache entries", deletedCount);
        } catch (Exception e) {
            log.warn("Error evicting multiple ticket caches: {}", e.getMessage());
        }
    }

    /**
     * Evict all tickets from cache using pattern-based invalidation.
     * Called on bulk operations when all ticket caches need to be cleared.
     */
    public void evictAllTickets() {
        try {
            Set<String> keys = redisTemplate.keys(TICKET_CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keys);
                log.info("Evicted all ticket cache entries (count: {})", deletedCount);
            }
        } catch (Exception e) {
            log.warn("Error evicting all ticket caches: {}", e.getMessage());
        }
    }

    /**
     * Evict tickets by pattern.
     * Useful for targeted bulk invalidation.
     *
     * @param pattern the key pattern (e.g., "ticket:customer:*")
     */
    public void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keys);
                log.debug("Evicted cache entries matching pattern '{}' (count: {})", pattern, deletedCount);
            }
        } catch (Exception e) {
            log.warn("Error evicting cache by pattern {}: {}", pattern, e.getMessage());
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Check if a ticket is cached.
     *
     * @param id the ticket ID
     * @return true if cached, false otherwise
     */
    public boolean isTicketCached(Long id) {
        String key = buildTicketKey(id);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Error checking ticket cache for ID {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Get the remaining TTL for a cached ticket.
     *
     * @param id the ticket ID
     * @return TTL in seconds, or -1 if not cached, -2 if no TTL set
     */
    public Long getTicketTtl(Long id) {
        String key = buildTicketKey(id);
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error getting TTL for ticket ID {}: {}", id, e.getMessage());
            return -1L;
        }
    }

    /**
     * Refresh the TTL for a cached ticket.
     *
     * @param id the ticket ID
     * @param ttlMinutes the new TTL in minutes
     */
    public void refreshTicketTtl(Long id, long ttlMinutes) {
        String key = buildTicketKey(id);
        try {
            redisTemplate.expire(key, ttlMinutes, TimeUnit.MINUTES);
            log.debug("Refreshed TTL for ticket ID: {} (new TTL: {} minutes)", id, ttlMinutes);
        } catch (Exception e) {
            log.warn("Error refreshing TTL for ticket ID {}: {}", id, e.getMessage());
        }
    }

    // ==================== Key Building ====================

    private String buildTicketKey(Long id) {
        return TICKET_CACHE_PREFIX + id;
    }

    private String buildTicketNumberKey(String ticketNumber) {
        return TICKET_NUMBER_PREFIX + ticketNumber;
    }
}

