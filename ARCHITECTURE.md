# Distributed Ticketing Management System - Architecture

## Clean Architecture Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENT                                         │
│                            (REST API Request)                                    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              CONTROLLER LAYER                                    │
│                            (TicketController.java)                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │ Responsibilities:                                                        │    │
│  │   • Receive HTTP requests                                               │    │
│  │   • Extract path/query parameters                                       │    │
│  │   • Delegate to Service layer                                           │    │
│  │   • Return HTTP responses                                               │    │
│  │                                                                          │    │
│  │ Does NOT:                                                                │    │
│  │   ✗ Validate input (done by ValidationService)                          │    │
│  │   ✗ Handle exceptions (done by GlobalExceptionHandler)                  │    │
│  │   ✗ Contain business logic                                              │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                               SERVICE LAYER                                      │
│                           (TicketServiceImp.java)                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │ Responsibilities:                                                        │    │
│  │   • Call ValidationService FIRST                                        │    │
│  │   • Execute business logic                                              │    │
│  │   • Map DTOs ↔ Entities                                                 │    │
│  │   • Interact with Repository                                            │    │
│  │   • Manage caching                                                       │    │
│  │                                                                          │    │
│  │ Assumes:                                                                 │    │
│  │   ✓ Input is validated (by ValidationService)                           │    │
│  │   ✓ Exceptions bubble up to GlobalExceptionHandler                      │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                         ┌─────────────┴─────────────┐
                         ▼                           ▼
┌────────────────────────────────────┐  ┌────────────────────────────────────────┐
│      VALIDATION SERVICE            │  │         CACHE SERVICE                   │
│   (TicketValidationService.java)   │  │     (TicketCacheService.java)          │
│  ┌──────────────────────────────┐  │  │  ┌──────────────────────────────────┐  │
│  │ SINGLE SOURCE OF TRUTH       │  │  │  │ Redis Cache-Aside Pattern       │  │
│  │ for ALL validations:         │  │  │  │                                  │  │
│  │                              │  │  │  │   • getTicketById(id)            │  │
│  │   • Null checks              │  │  │  │   • getTicketByNumber(number)    │  │
│  │   • Field validation         │  │  │  │   • cacheTicket(id, dto)         │  │
│  │   • Business rules           │  │  │  │   • evictTicket(id, number)      │  │
│  │   • Status transitions       │  │  │  │                                  │  │
│  │   • Duplicate checks         │  │  │  │ TTL: 30 minutes                  │  │
│  │   • SLA calculations         │  │  │  └──────────────────────────────────┘  │
│  └──────────────────────────────┘  │  └────────────────────────────────────────┘
└────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                             REPOSITORY LAYER                                     │
│                          (TicketRepository.java)                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │ Spring Data JPA Repository:                                              │    │
│  │   • findById, findAll, save, delete                                     │    │
│  │   • findByTicketNumber, findByStatus, findByPriority                    │    │
│  │   • findByCustomerId, findByAssignedTo                                  │    │
│  │   • existsByTicketNumber                                                 │    │
│  │   • Specification-based filtering                                       │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 DATABASE                                         │
│                                  (MySQL)                                         │
└─────────────────────────────────────────────────────────────────────────────────┘


## Exception Handling Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          EXCEPTION FLOW                                          │
└─────────────────────────────────────────────────────────────────────────────────┘

Any Exception thrown anywhere in the application:
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         GLOBAL EXCEPTION HANDLER                                 │
│                       (GlobalExceptionHandler.java)                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │ Extends: ResponseEntityExceptionHandler                                  │    │
│  │                                                                          │    │
│  │ Handles Spring MVC Exceptions:                                           │    │
│  │   • HttpMessageNotReadableException → 400 (empty/invalid JSON)          │    │
│  │   • MethodArgumentNotValidException → 400 (validation errors)           │    │
│  │   • MissingServletRequestParameterException → 400                        │    │
│  │   • HttpMediaTypeNotSupportedException → 415                             │    │
│  │   • HttpRequestMethodNotSupportedException → 405                         │    │
│  │                                                                          │    │
│  │ Handles Business Exceptions:                                             │    │
│  │   • NullRequestException → 400                                          │    │
│  │   • IllegalArgumentException → 400                                      │    │
│  │   • TicketNotFoundException → 404                                       │    │
│  │   • DuplicateTicketException → 409                                      │    │
│  │   • InvalidTicketOperationException → 400                               │    │
│  │                                                                          │    │
│  │ Handles System Exceptions:                                               │    │
│  │   • NullPointerException → 500                                          │    │
│  │   • RuntimeException → 500                                              │    │
│  │   • Exception → 500                                                     │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CUSTOM ERROR CONTROLLER                                │
│                         (CustomErrorController.java)                             │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │ Fallback for errors not caught by GlobalExceptionHandler                │    │
│  │ Replaces Spring's default BasicErrorController                          │    │
│  │                                                                          │    │
│  │ Returns consistent ErrorResponse format                                  │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              ERROR RESPONSE                                      │
│  {                                                                               │
│    "timestamp": "2024-12-24T10:30:00",                                          │
│    "status": 400,                                                                │
│    "error": "Bad Request",                                                       │
│    "message": "Ticket number is required; Customer ID is required",             │
│    "path": "uri=/api/tickets"                                                   │
│  }                                                                               │
└─────────────────────────────────────────────────────────────────────────────────┘
```


## Key Design Principles

### 1. Single Responsibility
- **Controller**: HTTP handling only
- **Service**: Business logic only
- **ValidationService**: Validation only
- **Repository**: Data access only
- **GlobalExceptionHandler**: Exception handling only

### 2. Single Source of Truth
- **ValidationService** is the ONLY place where validation happens
- No duplicate validation in Controller or Service

### 3. Clean Error Handling
- Exceptions bubble up naturally
- GlobalExceptionHandler catches ALL exceptions
- Consistent error response format

### 4. Cache-Aside Pattern
```
GET Request:
  1. Check Redis cache
  2. If hit → return cached data
  3. If miss → query MySQL → cache result → return data

CREATE/UPDATE:
  1. Validate → Execute → Save to MySQL
  2. Cache the new/updated data

DELETE:
  1. Evict from cache
  2. Delete from MySQL
```

### 5. Exception Hierarchy
```
TicketingException (abstract base)
├── TicketNotFoundException
├── DuplicateTicketException
├── InvalidTicketOperationException
└── NullRequestException
```


## File Structure

```
src/main/java/org/example/distributedticketingmanagementsystem/
├── controller/
│   └── TicketController.java          # REST endpoints (thin layer)
├── service/
│   ├── TicketService.java             # Interface
│   ├── TicketServiceImp.java          # Business logic
│   ├── TicketValidationService.java   # All validations (single source)
│   └── TicketCacheService.java        # Redis caching
├── repository/
│   └── TicketRepository.java          # JPA repository
├── entity/
│   └── Ticket.java                    # JPA entity
├── dto/
│   ├── TicketDTO.java
│   ├── TicketCreateRequest.java
│   ├── TicketUpdateRequest.java
│   ├── ServiceResponse.java           # Generic response wrapper
│   └── ...
├── mapper/
│   └── TicketMapper.java              # Entity ↔ DTO mapping
├── exception/
│   ├── TicketingException.java        # Base exception
│   ├── TicketNotFoundException.java
│   ├── DuplicateTicketException.java
│   ├── InvalidTicketOperationException.java
│   ├── NullRequestException.java
│   ├── ErrorResponse.java             # Error response DTO
│   ├── GlobalExceptionHandler.java    # Central exception handler
│   └── CustomErrorController.java     # Fallback error controller
└── config/
    ├── RedisConfig.java
    └── KafkaConfig.java
```

