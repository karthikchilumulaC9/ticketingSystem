# 🔄 Kafka Bulk Processing Workflow Documentation

> **Document Version:** 1.0  
> **Last Updated:** December 26, 2025  
> **Author:** Enterprise Architecture Team

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Complete Workflow](#complete-workflow)
4. [Component Details](#component-details)
5. [Configuration](#configuration)
6. [Error Handling](#error-handling)
7. [API Reference](#api-reference)
8. [Design Analysis](#design-analysis)

---

## Overview

This document describes the complete behind-the-scenes workflow when a CSV file is uploaded to the bulk ticket creation endpoint. The system uses **Apache Kafka** for asynchronous, scalable processing with **Redis** for distributed state tracking.

### Key Features
- **Asynchronous Processing**: Immediate HTTP 202 response, background processing
- **Chunking**: Large files split into 100-record chunks for parallel processing
- **5 Kafka Partitions**: Parallel consumption across multiple consumers
- **Dead Letter Queue (DLT)**: Failed messages stored for manual reprocessing
- **Redis Tracking**: Real-time progress monitoring
- **Exponential Backoff Retry**: 3 retries with 1s → 2s → 4s intervals

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           KAFKA BULK PROCESSING ARCHITECTURE                     │
└─────────────────────────────────────────────────────────────────────────────────┘

                                    ┌──────────────┐
                                    │   Frontend   │
                                    │ (CSV Upload) │
                                    └──────┬───────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              REST API LAYER                                      │
│  ┌────────────────────────────────────────────────────────────────────────────┐ │
│  │  BulkTicketController                                                      │ │
│  │  POST /api/tickets/bulk/upload                                             │ │
│  │  - Accepts MultipartFile (CSV)                                             │ │
│  │  - Returns batch ID immediately (HTTP 202 Accepted)                        │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           PROCESSING SERVICE                                     │
│  ┌────────────────────────────────────────────────────────────────────────────┐ │
│  │  BulkUploadProcessingService                                               │ │
│  │  1. validateFile() - Size, format, extension                               │ │
│  │  2. parseAndValidateCsv() - Parse CSV, validate rows                       │ │
│  │  3. Validate batch size (max 10,000 records)                               │ │
│  │  4. Calculate chunks (100 records per chunk)                               │ │
│  │  5. Call Kafka Producer                                                    │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              KAFKA PRODUCER                                      │
│  ┌────────────────────────────────────────────────────────────────────────────┐ │
│  │  TicketKafkaProducer.sendBulkUploadEvent()                                 │ │
│  │  1. Generate batch ID (BATCH-{timestamp}-{uuid})                           │ │
│  │  2. chunkList() - Split into 100-record chunks                             │ │
│  │  3. For each chunk:                                                        │ │
│  │     - Create BulkTicketUploadEvent                                         │ │
│  │     - Set chunk key: {batchId}-CHUNK-{chunkNumber}                         │ │
│  │     - kafkaTemplate.send(topic, key, event)                                │ │
│  │  4. Return batch ID                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                               KAFKA BROKER                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  Topic: ticket.bulk.requests (or ticket-bulk-upload)                    │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │   │
│  │  │ Part-0  │ │ Part-1  │ │ Part-2  │ │ Part-3  │ │ Part-4  │           │   │
│  │  │ Chunk-0 │ │ Chunk-1 │ │ Chunk-2 │ │ Chunk-3 │ │ Chunk-4 │           │   │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘           │   │
│  └───────┼──────────────────────────────────────────────────────────────────┘   │
│          │ Parallel Consumption (5 partitions, 3 consumers/instance)             │
└──────────┼──────────────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              KAFKA CONSUMER                                      │
│  ┌────────────────────────────────────────────────────────────────────────────┐ │
│  │  BulkTicketKafkaConsumer.consumeBulkTicketEvent()                          │ │
│  │  @KafkaListener(concurrency = "3")                                         │ │
│  │                                                                            │ │
│  │  For each chunk:                                                           │ │
│  │  1. validateEvent() - Check null, batchId, tickets                         │ │
│  │  2. initializeTracking() - Initialize Redis batch status                   │ │
│  │  3. isBatchCancelled() - Check if user cancelled                           │ │
│  │  4. processTickets() - Loop through each ticket:                           │ │
│  │     - ticketService.createTicket(request)                                  │ │
│  │     - Handle exceptions per ticket                                         │ │
│  │     - Track success/failure in Redis                                       │ │
│  │  5. completeChunk() - Update Redis progress                                │ │
│  │  6. acknowledgment.acknowledge() - Manual ACK                              │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                           │
                    ┌──────────────────────┴────────────────────┐
                    ▼                                          ▼
┌─────────────────────────────────┐          ┌─────────────────────────────────┐
│      SUCCESS PATH               │          │      FAILURE PATH                │
│  ┌─────────────────────────┐   │          │  ┌─────────────────────────────┐ │
│  │ PostgreSQL Database     │   │          │  │ Retry (3 attempts)          │ │
│  │ - Ticket saved          │   │          │  │ Exponential Backoff:        │ │
│  │ - Transaction commits   │   │          │  │ 1s → 2s → 4s (max 10s)      │ │
│  └──────────┬──────────────┘   │          │  └──────────┬──────────────────┘ │
│             ▼                   │          │             │                    │
│  ┌─────────────────────────┐   │          │             ▼                    │
│  │ Redis Cache             │   │          │  ┌─────────────────────────────┐ │
│  │ - Ticket cached         │   │          │  │ Dead Letter Topic (DLT)     │ │
│  │ - After TX commit       │   │          │  │ ticket.bulk.requests.DLT    │ │
│  └──────────┬──────────────┘   │          │  │ - 30 day retention          │ │
│             ▼                   │          │  │ - Manual reprocessing       │ │
│  ┌─────────────────────────┐   │          │  └─────────────────────────────┘ │
│  │ Redis Tracking          │   │          └─────────────────────────────────┘
│  │ - successCount++        │   │
│  │ - Batch progress        │   │
│  └─────────────────────────┘   │
└─────────────────────────────────┘
```

---

## Complete Workflow

### Step-by-Step Process When You Upload a CSV

#### STEP 1: Controller Receives Request
**File:** `BulkTicketController.java`

```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ApiResponseWrapper<BulkUploadResponse>> uploadBulkTickets(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "uploadedBy", required = false, defaultValue = "system") String uploadedBy)
```

**What happens:**
- Receives the MultipartFile (your CSV)
- Logs: `📤 BULK UPLOAD REQUEST - file: {}, size: {} bytes, uploadedBy: {}`
- Calls `processingService.processBulkUpload(file, uploadedBy)`

---

#### STEP 2: Processing Service Validates & Parses
**File:** `BulkUploadProcessingService.java`

**2A. File Validation (`validateFile`)**
```
├── Check if file is empty → CsvValidationException
├── Check extension (.csv or .txt) → CsvValidationException
└── Check size (max 10 MB) → CsvValidationException
```

**2B. CSV Parsing (`parseAndValidateCsv`)**
```
├── Read CSV header row
├── Validate required columns exist:
│   • ticketnumber ✓
│   • title ✓
│   • customerid ✓
├── For each row:
│   ├── Parse ticketNumber (required)
│   ├── Parse title (required, max 255 chars)
│   ├── Parse customerId (required, must be positive number)
│   ├── Parse description (optional, truncated at 5000 chars)
│   ├── Parse status (optional, default: "OPEN")
│   ├── Parse priority (optional, default: "MEDIUM")
│   ├── Parse assignedTo (optional)
│   ├── Check for duplicate ticketNumber within file
│   └── Create TicketCreateRequest object
└── Return List<TicketCreateRequest>
```

**2C. Batch Size Validation**
- Maximum 10,000 records allowed
- Throws `KafkaBulkProcessingException` if exceeded

**2D. Calculate Chunks**
```java
int totalChunks = (int) Math.ceil((double) tickets.size() / 100);
// Example: 500 tickets → 5 chunks of 100 each
```

---

#### STEP 3: Kafka Producer Sends Chunks
**File:** `TicketKafkaProducer.java`

```java
public String sendBulkUploadEvent(
        List<TicketCreateRequest> requests,
        String uploadedBy,
        String originalFilename) {

    // 3A. Generate unique batch ID
    String batchId = generateBatchId();
    // Format: "BATCH-1735123456789-A1B2C3D4"

    // 3B. Chunk the requests
    List<List<TicketCreateRequest>> chunks = chunkList(requests, chunkSize);

    // 3C. Send each chunk to Kafka
    for (int i = 0; i < chunks.size(); i++) {
        BulkTicketUploadEvent event = BulkTicketUploadEvent.create(
                batchId, i, totalChunks, chunks.get(i), uploadedBy, originalFilename);

        String key = event.getChunkKey(); // "BATCH-xxx-CHUNK-0"

        kafkaTemplate.send(bulkUploadTopic, key, event)
                .whenComplete((result, ex) -> { /* logging */ });
    }

    return batchId;
}
```

**BulkTicketUploadEvent Structure:**
```json
{
  "eventId": "uuid",
  "eventType": "BULK_TICKET_UPLOAD",
  "timestamp": "2025-12-26T10:30:00",
  "correlationId": "BATCH-1735123456789-A1B2C3D4",
  "batchId": "BATCH-1735123456789-A1B2C3D4",
  "chunkNumber": 0,
  "totalChunks": 5,
  "tickets": [ ... 100 TicketCreateRequest objects ... ],
  "uploadedBy": "karthik",
  "originalFilename": "sample_bulk_upload.csv"
}
```

---

#### STEP 4: Kafka Broker Distributes Messages

```
Topic: ticket-bulk-upload (5 partitions)

┌─────────────────────────────────────────────────────────────────────────┐
│  Partition 0     Partition 1     Partition 2     Partition 3     Part 4 │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌───────┐ │
│  │ Chunk-2 │    │ Chunk-0 │    │ Chunk-4 │    │ Chunk-1 │    │Chunk-3│ │
│  └─────────┘    └─────────┘    └─────────┘    └─────────┘    └───────┘ │
└─────────────────────────────────────────────────────────────────────────┘

(Chunks distributed across partitions based on key hash)
```

---

#### STEP 5: Kafka Consumer Processes Chunks
**File:** `BulkTicketKafkaConsumer.java`

```java
@KafkaListener(
        topics = "${app.kafka.topics.bulk-requests:ticket.bulk.requests}",
        groupId = "${app.kafka.consumer.bulk-group-id:ticket-bulk-consumers}",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "3"
)
public void consumeBulkTicketEvent(
        @Payload BulkTicketUploadEvent event,
        @Header(KafkaHeaders.RECEIVED_KEY) String key,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
        Acknowledgment acknowledgment)
```

**Processing Flow:**

**5A. Validate Event**
```java
validateEvent(event);
// Check event not null, batchId exists, tickets list exists
```

**5B. Initialize Tracking**
```java
trackingService.initializeBatch(batchId, totalChunks, totalTickets);
// Saves to Redis: bulk:batch:status:{batchId}
```

**5C. Check Cancellation**
```java
if (isBatchCancelled(event.getBatchId())) {
    acknowledgment.acknowledge();
    return; // Skip processing
}
```

**5D. Process Each Ticket**
```java
for (TicketCreateRequest request : event.getTickets()) {
    try {
        ticketService.createTicket(request);
        // This triggers:
        // 1. validationService.validateCreateRequest(request)
        // 2. ticketMapper.toEntity(request)
        // 3. ticketRepository.save(ticket) → POSTGRES
        // 4. eventPublisher.publishEvent(TicketCreatedEvent)
        // 5. After TX commit → TicketEventListener caches to REDIS

        trackingService.recordSuccess(batchId, ticketNumber);

    } catch (DuplicateTicketException e) {
        // Skip, mark as duplicate
        trackingService.recordFailure(batchId, ticketNumber, "DUPLICATE", msg);

    } catch (NullRequestException e) {
        // Fail, record error
        trackingService.recordFailure(batchId, ticketNumber, "NULL_REQUEST", msg);

    } catch (DataIntegrityViolationException e) {
        // Fail, record error
        trackingService.recordFailure(batchId, ticketNumber, "DATA_INTEGRITY", msg);

    } catch (RuntimeException e) {
        // Fail, categorize error
        trackingService.recordFailure(batchId, ticketNumber, errorCode, msg);
    }
}
```

**5E. Complete Chunk**
```java
trackingService.completeChunk(batchId, chunkNumber);
// Updates Redis: bulk:batch:progress:{batchId}
// Increments completedChunks counter
// If all chunks done → status = "COMPLETED"
```

**5F. Acknowledge Message**
```java
acknowledgment.acknowledge();
// Tells Kafka: "I processed this message successfully"
```

---

## Component Details

### Files Involved

| Component | File | Purpose |
|-----------|------|---------|
| Controller | `BulkTicketController.java` | REST API endpoints |
| Processing Service | `BulkUploadProcessingService.java` | CSV validation & parsing |
| Kafka Producer | `TicketKafkaProducer.java` | Sends chunks to Kafka |
| Kafka Producer (Alt) | `BulkTicketKafkaProducer.java` | Enterprise version with more features |
| Kafka Consumer | `BulkTicketKafkaConsumer.java` | Processes chunks from Kafka |
| Tracking Service | `BulkUploadTrackingService.java` | Redis-based progress tracking |
| Configuration | `KafkaBulkProcessingConfig.java` | Kafka topics, consumers, error handling |
| Event | `BulkTicketUploadEvent.java` | Kafka message payload |
| Error Codes | `BulkProcessingErrorCode.java` | Error categorization |

### Redis Keys Used

| Key Pattern | Purpose | TTL |
|-------------|---------|-----|
| `bulk:batch:status:{batchId}` | BatchStatus object | 24 hours |
| `bulk:batch:progress:{batchId}` | Set of completed chunk numbers | 24 hours |
| `bulk:batch:failures:{batchId}` | List of failure details | 24 hours |
| `bulk:active-batches` | Set of active batch IDs | No expiry |
| `bulk:dlt:{topic}` | List of DLT messages | 7 days |

---

## Configuration

### Kafka Topics

| Topic | Partitions | Replicas | Retention | Purpose |
|-------|------------|----------|-----------|---------|
| `ticket.bulk.requests` | 5 | 3 (prod) / 1 (dev) | 7 days | Main processing |
| `ticket.bulk.requests.DLT` | 1 | 1 | 30 days | Dead Letter Queue |
| `ticket.bulk.notifications` | 3 | 1 | 7 days | Completion notifications |

### Consumer Configuration

| Setting | Value | Purpose |
|---------|-------|---------|
| Group ID | `ticket-bulk-consumers` | Consumer group |
| Concurrency | 3 | Threads per instance |
| Batch Size | 100 | Records per poll |
| Poll Timeout | 1000ms | Max wait time |
| Auto Commit | false | Manual acknowledgment |
| Offset Reset | earliest | Start from beginning |

### Retry Configuration

| Setting | Value |
|---------|-------|
| Max Attempts | 3 |
| Initial Interval | 1000ms |
| Multiplier | 2.0 |
| Max Interval | 10000ms |
| Backoff Pattern | 1s → 2s → 4s |

---

## Error Handling

### Error Categories

#### Validation Errors (1xxx) - Non-Retryable
| Code | Name | Description |
|------|------|-------------|
| V1001 | EMPTY_FILE | File is empty |
| V1002 | INVALID_FILE_FORMAT | Invalid file format |
| V1003 | MISSING_REQUIRED_COLUMNS | Missing required CSV columns |
| V1004 | INVALID_ROW_DATA | Invalid row data |
| V1005 | MISSING_TICKET_NUMBER | Ticket number required |
| V1006 | INVALID_CUSTOMER_ID | Invalid customer ID |
| V1007 | MISSING_TITLE | Title required |
| V1008 | NULL_REQUEST | Request is null |
| V1009 | BATCH_SIZE_EXCEEDED | Batch too large |

#### Processing Errors (2xxx) - Mixed
| Code | Name | Retryable | Description |
|------|------|-----------|-------------|
| P2001 | DUPLICATE_TICKET | No | Duplicate ticket number |
| P2002 | TICKET_CREATION_FAILED | Yes | Failed to create ticket |
| P2003 | CHUNK_PROCESSING_FAILED | Yes | Chunk processing failed |
| P2004 | BATCH_PROCESSING_FAILED | Yes | Batch processing failed |
| P2005 | RECORD_PROCESSING_FAILED | Yes | Record processing failed |

#### Infrastructure Errors (3xxx) - Retryable
| Code | Name | Description |
|------|------|-------------|
| I3001 | KAFKA_PRODUCER_ERROR | Kafka send failed |
| I3002 | DATABASE_ERROR | Database operation failed |
| I3003 | IO_ERROR | I/O operation failed |
| I3004 | TIMEOUT_ERROR | Operation timed out |

### Exception Flow

```
Exception Thrown
       │
       ▼
┌──────────────────────┐
│ Is it retryable?     │
└──────────┬───────────┘
           │
     ┌─────┴─────┐
     ▼           ▼
   YES          NO
     │           │
     ▼           ▼
┌─────────┐  ┌─────────────┐
│ Retry   │  │ Acknowledge │
│ (1-3x)  │  │ + Record    │
└────┬────┘  │ Failure     │
     │       └─────────────┘
     ▼
┌─────────────────┐
│ Still failing?  │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
   YES       NO
    │         │
    ▼         ▼
┌───────┐  ┌─────────┐
│  DLT  │  │ Success │
└───────┘  └─────────┘
```

---

## API Reference

### Upload CSV
```http
POST /api/tickets/bulk/upload
Content-Type: multipart/form-data

file: <CSV file>
uploadedBy: karthik (optional, default: "system")
```

**Response (HTTP 202 Accepted):**
```json
{
  "success": true,
  "message": "Bulk upload accepted for processing",
  "status": "ACCEPTED",
  "data": {
    "batchId": "BATCH-1735123456789-A1B2C3D4",
    "status": "ACCEPTED",
    "statusMessage": "Upload accepted and queued for processing",
    "totalRecords": 500,
    "totalChunks": 5,
    "originalFilename": "sample_bulk_upload.csv",
    "uploadedBy": "karthik",
    "acceptedAt": "2025-12-26T10:30:00",
    "statusUrl": "/api/tickets/bulk/status/BATCH-1735123456789-A1B2C3D4",
    "failuresUrl": "/api/tickets/bulk/failures/BATCH-1735123456789-A1B2C3D4"
  },
  "timestamp": "2025-12-26T10:30:00.123Z"
}
```

### Check Status
```http
GET /api/tickets/bulk/status/{batchId}
```

**Response:**
```json
{
  "data": {
    "batchId": "BATCH-1735123456789-A1B2C3D4",
    "status": "COMPLETED",
    "totalRecords": 500,
    "successCount": 498,
    "failureCount": 2,
    "completedChunks": 5,
    "totalChunks": 5,
    "processingStartedAt": "2025-12-26T10:30:00",
    "completedAt": "2025-12-26T10:30:45"
  }
}
```

### Get Failures
```http
GET /api/tickets/bulk/failures/{batchId}?page=0&size=50
```

### Get Active Batches
```http
GET /api/tickets/bulk/active
```

### Cancel Batch
```http
POST /api/tickets/bulk/cancel/{batchId}?reason=User requested cancellation
```

### Get DLT Messages
```http
GET /api/tickets/bulk/dlt
```

---

## Design Analysis

### ✅ Strengths

| Aspect | Rating | Description |
|--------|--------|-------------|
| **Chunking Strategy** | ⭐⭐⭐⭐⭐ | 100 records per chunk prevents memory overflow |
| **Exception Handling** | ⭐⭐⭐⭐⭐ | Comprehensive categorization with retryable/non-retryable |
| **Dead Letter Queue** | ⭐⭐⭐⭐⭐ | Failed messages stored for manual reprocessing |
| **Manual Acknowledgment** | ⭐⭐⭐⭐⭐ | Message only ACKed after successful processing |
| **Redis Tracking** | ⭐⭐⭐⭐⭐ | Real-time progress with in-memory fallback |
| **Exponential Backoff** | ⭐⭐⭐⭐⭐ | Smart retry with increasing delays |
| **Idempotent Producer** | ⭐⭐⭐⭐⭐ | Prevents duplicate messages |
| **Cancellation Support** | ⭐⭐⭐⭐ | Users can cancel in-progress batches |

### ⚠️ Areas for Improvement

| Issue | Impact | Recommendation |
|-------|--------|----------------|
| No Transactional Outbox | Data loss on crash | Implement outbox pattern |
| No Kafka Transactions | Not exactly-once | Add transactional producer |
| Partition Key Strategy | Poor data locality | Use customerId instead of chunkKey |
| No Circuit Breaker | Cascade failures | Add Resilience4j |
| Limited Metrics | Poor observability | Add Micrometer metrics |

### Overall Rating: **4.5/5 - Production Ready**

---

## Timeline Example

| Time | Event | Component |
|------|-------|-----------|
| T+0ms | CSV uploaded | Controller |
| T+50ms | File validated | ProcessingService |
| T+200ms | CSV parsed (500 rows) | ProcessingService |
| T+300ms | 5 chunks created | Producer |
| T+500ms | All chunks sent to Kafka | Producer |
| T+500ms | **HTTP 202 returned to user** | Controller |
| T+600ms | Consumers start processing | Consumer (async) |
| T+1s | Chunk 0 complete (100 tickets) | Consumer |
| T+2s | Chunk 1 complete | Consumer |
| T+3s | Chunk 2 complete | Consumer |
| T+4s | Chunk 3 complete | Consumer |
| T+5s | Chunk 4 complete | Consumer |
| T+5s | Batch marked COMPLETED | TrackingService |

**Key Point:** User gets response at T+500ms. Actual processing happens asynchronously over ~5 seconds.

---

## Sample CSV Format

```csv
ticketnumber,title,customerid,description,status,priority,assignedto
TKT-001,Login Issue,1001,User cannot login to system,OPEN,HIGH,101
TKT-002,Password Reset,1002,Password reset not working,OPEN,MEDIUM,102
TKT-003,Dashboard Error,1003,Dashboard not loading,IN_PROGRESS,LOW,103
```

### Required Columns
- `ticketnumber` - Unique ticket identifier
- `title` - Ticket title (max 255 chars)
- `customerid` - Customer ID (positive number)

### Optional Columns
- `description` - Detailed description (max 5000 chars)
- `status` - OPEN, IN_PROGRESS, RESOLVED, CLOSED (default: OPEN)
- `priority` - LOW, MEDIUM, HIGH, CRITICAL (default: MEDIUM)
- `assignedto` - Agent ID to assign ticket

---

*Document generated for Distributed Ticketing Management System*

