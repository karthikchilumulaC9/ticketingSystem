package org.example.distributedticketingmanagementsystem.kafka.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.distributedticketingmanagementsystem.kafka.dto.BatchStatus;
import org.example.distributedticketingmanagementsystem.kafka.dto.DltMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for tracking bulk upload progress and DLT messages.
 * Uses Redis for distributed state management.
 *
 * Tracking includes:
 * - Batch status and progress
 * - Per-ticket success/failure tracking
 * - DLT message storage for analysis
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkUploadTrackingService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String BATCH_STATUS_KEY_PREFIX = "bulk:batch:status:";
    private static final String BATCH_PROGRESS_KEY_PREFIX = "bulk:batch:progress:";
    private static final String BATCH_FAILURES_KEY_PREFIX = "bulk:batch:failures:";
    private static final String DLT_MESSAGES_KEY_PREFIX = "bulk:dlt:";
    private static final String ACTIVE_BATCHES_KEY = "bulk:active-batches";

    private static final Duration BATCH_EXPIRY = Duration.ofHours(24);

    // In-memory fallback for when Redis is not available
    private final Map<String, BatchStatus> inMemoryBatchStatus = new ConcurrentHashMap<>();

    /**
     * Initializes tracking for a new batch.
     * Called when the first chunk of a batch is received.
     */
    public void initializeBatch(String batchId, int totalChunks, int estimatedTotalTickets) {
        String statusKey = BATCH_STATUS_KEY_PREFIX + batchId;

        // Check if already initialized
        if (Boolean.TRUE.equals(redisTemplate.hasKey(statusKey))) {
            log.debug("Batch already initialized: {}", batchId);
            return;
        }

        BatchStatus status = BatchStatus.builder()
                .batchId(batchId)
                .status("IN_PROGRESS")
                .totalChunks(totalChunks)
                .completedChunks(0)
                .totalTickets(estimatedTotalTickets)
                .successCount(0)
                .failureCount(0)
                .startTime(LocalDateTime.now())
                .build();

        try {
            redisTemplate.opsForValue().set(statusKey, status, BATCH_EXPIRY);
            redisTemplate.opsForSet().add(ACTIVE_BATCHES_KEY, batchId);
            log.info("📊 BATCH INITIALIZED - batchId: {}, chunks: {}, estimatedTickets: {}",
                    batchId, totalChunks, estimatedTotalTickets);
        } catch (Exception e) {
            log.warn("Redis unavailable, using in-memory tracking for batch: {}", batchId);
            inMemoryBatchStatus.put(batchId, status);
        }
    }

    /**
     * Records a successful ticket creation.
     */
    public void recordSuccess(String batchId, String ticketNumber) {
        try {
            String statusKey = BATCH_STATUS_KEY_PREFIX + batchId;
            redisTemplate.opsForHash().increment(statusKey, "successCount", 1);
            log.debug("Recorded success for batch: {}, ticket: {}", batchId, ticketNumber);
        } catch (Exception e) {
            log.debug("In-memory success tracking for batch: {}", batchId);
            inMemoryBatchStatus.computeIfPresent(batchId, (k, v) -> {
                v.setSuccessCount(v.getSuccessCount() + 1);
                return v;
            });
        }
    }

    /**
     * Records a failed ticket creation.
     */
    public void recordFailure(String batchId, String ticketNumber, String errorCode, String errorMessage) {
        try {
            String statusKey = BATCH_STATUS_KEY_PREFIX + batchId;
            String failuresKey = BATCH_FAILURES_KEY_PREFIX + batchId;

            redisTemplate.opsForHash().increment(statusKey, "failureCount", 1);

            Map<String, String> failureInfo = new HashMap<>();
            failureInfo.put("ticketNumber", ticketNumber);
            failureInfo.put("errorCode", errorCode);
            failureInfo.put("errorMessage", errorMessage);
            failureInfo.put("timestamp", LocalDateTime.now().toString());

            redisTemplate.opsForList().rightPush(failuresKey, failureInfo);
            redisTemplate.expire(failuresKey, BATCH_EXPIRY);

            log.debug("Recorded failure for batch: {}, ticket: {}, error: {}",
                    batchId, ticketNumber, errorCode);
        } catch (Exception e) {
            log.debug("In-memory failure tracking for batch: {}", batchId);
            inMemoryBatchStatus.computeIfPresent(batchId, (k, v) -> {
                v.setFailureCount(v.getFailureCount() + 1);
                return v;
            });
        }
    }

    /**
     * Marks a chunk as completed.
     */
    public void completeChunk(String batchId, int chunkNumber) {
        try {
            String statusKey = BATCH_STATUS_KEY_PREFIX + batchId;
            String progressKey = BATCH_PROGRESS_KEY_PREFIX + batchId;

            // Mark chunk as complete
            redisTemplate.opsForSet().add(progressKey, chunkNumber);
            redisTemplate.expire(progressKey, BATCH_EXPIRY);

            // Increment completed chunks count
            Long completedChunks = redisTemplate.opsForHash().increment(statusKey, "completedChunks", 1);

            // Check if all chunks are complete
            Object totalChunksObj = redisTemplate.opsForHash().get(statusKey, "totalChunks");
            int totalChunks = totalChunksObj != null ? ((Number) totalChunksObj).intValue() : 0;

            if (completedChunks != null && completedChunks >= totalChunks) {
                redisTemplate.opsForHash().put(statusKey, "status", "COMPLETED");
                redisTemplate.opsForHash().put(statusKey, "endTime", LocalDateTime.now().toString());
                redisTemplate.opsForSet().remove(ACTIVE_BATCHES_KEY, batchId);

                log.info("📊 BATCH COMPLETED - batchId: {}", batchId);
            }

            log.debug("Chunk completed - batch: {}, chunk: {}/{}", batchId, chunkNumber + 1, totalChunks);
        } catch (Exception e) {
            log.debug("In-memory chunk tracking for batch: {}", batchId);
            inMemoryBatchStatus.computeIfPresent(batchId, (k, v) -> {
                v.setCompletedChunks(v.getCompletedChunks() + 1);
                if (v.getCompletedChunks() >= v.getTotalChunks()) {
                    v.setStatus("COMPLETED");
                    v.setEndTime(LocalDateTime.now());
                }
                return v;
            });
        }
    }

    /**
     * Gets the current status of a batch.
     */
    public Optional<BatchStatus> getBatchStatus(String batchId) {
        try {
            String statusKey = BATCH_STATUS_KEY_PREFIX + batchId;
            Object status = redisTemplate.opsForValue().get(statusKey);

            if (status instanceof BatchStatus) {
                return Optional.of((BatchStatus) status);
            } else if (status instanceof Map) {
                // Handle Redis hash conversion
                return Optional.of(mapToBatchStatus(batchId, (Map<?, ?>) status));
            }
        } catch (Exception e) {
            log.debug("Checking in-memory for batch status: {}", batchId);
        }

        return Optional.ofNullable(inMemoryBatchStatus.get(batchId));
    }

    /**
     * Gets all active batch IDs.
     */
    public Set<String> getActiveBatches() {
        try {
            Set<Object> batches = redisTemplate.opsForSet().members(ACTIVE_BATCHES_KEY);
            if (batches != null) {
                Set<String> result = new HashSet<>();
                for (Object batch : batches) {
                    result.add(batch.toString());
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("Checking in-memory for active batches");
        }

        return inMemoryBatchStatus.keySet();
    }

    /**
     * Gets failures for a batch.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getBatchFailures(String batchId) {
        try {
            String failuresKey = BATCH_FAILURES_KEY_PREFIX + batchId;
            List<Object> failures = redisTemplate.opsForList().range(failuresKey, 0, -1);

            if (failures != null) {
                return failures.stream()
                        .filter(f -> f instanceof Map)
                        .map(f -> (Map<String, String>) f)
                        .toList();
            }
        } catch (Exception e) {
            log.debug("Unable to fetch failures from Redis for batch: {}", batchId);
        }

        return Collections.emptyList();
    }

    /**
     * Records a message that ended up in the Dead Letter Topic.
     */
    public void recordDltMessage(String topic, String key, Object value) {
        try {
            String dltKey = DLT_MESSAGES_KEY_PREFIX + topic;

            DltMessage dltMessage = DltMessage.builder()
                    .topic(topic)
                    .messageKey(key)
                    .payload(value != null ? value.toString() : null)
                    .timestamp(LocalDateTime.now())
                    .processed(false)
                    .build();

            redisTemplate.opsForList().rightPush(dltKey, dltMessage);
            redisTemplate.expire(dltKey, Duration.ofDays(7));

            log.info("☠️ DLT MESSAGE RECORDED - topic: {}, key: {}", topic, key);
        } catch (Exception e) {
            log.error("Failed to record DLT message: {}", e.getMessage());
        }
    }

    /**
     * Gets DLT messages for a topic.
     */
    @SuppressWarnings("unchecked")
    public List<DltMessage> getDltMessages(String topic) {
        try {
            String dltKey = DLT_MESSAGES_KEY_PREFIX + topic;
            List<Object> messages = redisTemplate.opsForList().range(dltKey, 0, -1);

            if (messages != null) {
                return messages.stream()
                        .filter(m -> m instanceof DltMessage)
                        .map(m -> (DltMessage) m)
                        .toList();
            }
        } catch (Exception e) {
            log.debug("Unable to fetch DLT messages from Redis for topic: {}", topic);
        }

        return Collections.emptyList();
    }

    /**
     * Converts a Map to BatchStatus (for Redis hash deserialization).
     */
    private BatchStatus mapToBatchStatus(String batchId, Map<?, ?> map) {
        return BatchStatus.builder()
                .batchId(batchId)
                .status(getStringValue(map, "status"))
                .totalChunks(getIntValue(map, "totalChunks"))
                .completedChunks(getIntValue(map, "completedChunks"))
                .totalTickets(getIntValue(map, "totalTickets"))
                .successCount(getIntValue(map, "successCount"))
                .failureCount(getIntValue(map, "failureCount"))
                .build();
    }

    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private int getIntValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}

