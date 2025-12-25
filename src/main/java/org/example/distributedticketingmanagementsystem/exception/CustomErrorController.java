package org.example.distributedticketingmanagementsystem.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
public class CustomErrorController {

    /**
     * Handles all errors that reach the /error endpoint.
     * This is the fallback when exceptions are not caught by GlobalExceptionHandler.
     */
    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {

        // DEBUG: Confirm this handler is being called
        System.out.println("========================================");
        System.out.println("CustomErrorController.handleError() CALLED!");
        System.out.println("This means GlobalExceptionHandler did NOT catch the exception!");
        System.out.println("========================================");

        log.debug("🔍 ERROR CONTROLLER DEBUG - handleError() ENTERED");
        log.debug("🔍 ERROR CONTROLLER DEBUG - Request URI: {}", request.getRequestURI());
        log.debug("🔍 ERROR CONTROLLER DEBUG - Method: {}", request.getMethod());

        // Get error attributes from request
        Object statusObj = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object messageObj = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Throwable exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Object requestUriObj = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        log.debug("🔍 ERROR CONTROLLER DEBUG - Error attributes:");
        log.debug("🔍 ERROR CONTROLLER DEBUG   - STATUS_CODE: {}", statusObj);
        log.debug("🔍 ERROR CONTROLLER DEBUG   - MESSAGE: {}", messageObj);
        log.debug("🔍 ERROR CONTROLLER DEBUG   - EXCEPTION: {}", exception);
        log.debug("🔍 ERROR CONTROLLER DEBUG   - REQUEST_URI: {}", requestUriObj);

        if (exception != null) {
            log.debug("🔍 ERROR CONTROLLER DEBUG   - Exception class: {}", exception.getClass().getName());
            log.debug("🔍 ERROR CONTROLLER DEBUG   - Exception message: {}", exception.getMessage());
            if (exception.getCause() != null) {
                log.debug("🔍 ERROR CONTROLLER DEBUG   - Cause class: {}", exception.getCause().getClass().getName());
                log.debug("🔍 ERROR CONTROLLER DEBUG   - Cause message: {}", exception.getCause().getMessage());
            }
        }

        // Parse status code
        int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
        if (statusObj != null) {
            try {
                statusCode = Integer.parseInt(statusObj.toString());
            } catch (NumberFormatException e) {
                log.warn("Could not parse status code: {}", statusObj);
            }
        }

        // Get request URI
        String requestUri = requestUriObj != null ? requestUriObj.toString() : request.getRequestURI();

        // Get HTTP status
        HttpStatus httpStatus;
        try {
            httpStatus = HttpStatus.valueOf(statusCode);
        } catch (IllegalArgumentException e) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // Determine the error message
        String message;
        if (exception != null) {
            message = getExceptionMessage(exception);
            log.error("❌ ERROR CONTROLLER - Status: {}, Path: {}, Exception: {}",
                    statusCode, requestUri, exception.getMessage());
        } else if (messageObj != null && !messageObj.toString().isEmpty()) {
            message = getCleanMessage(messageObj.toString());
            log.error("❌ ERROR CONTROLLER - Status: {}, Path: {}, Message: {}",
                    statusCode, requestUri, message);
        } else {
            message = getDefaultMessage(httpStatus);
            log.error("❌ ERROR CONTROLLER - Status: {}, Path: {}", statusCode, requestUri);
        }

        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                statusCode,
                httpStatus.getReasonPhrase(),
                message,
                "uri=" + requestUri
        );

        return new ResponseEntity<>(errorResponse, httpStatus);
    }

    /**
     * Extracts a user-friendly message from the exception.
     */
    private String getExceptionMessage(Throwable exception) {
        if (exception == null) {
            return "An unexpected error occurred";
        }

        String message = exception.getMessage();
        if (message == null || message.isEmpty()) {
            // Check cause
            Throwable cause = exception.getCause();
            if (cause != null && cause.getMessage() != null) {
                message = cause.getMessage();
            } else {
                return "An unexpected error occurred";
            }
        }

        return getCleanMessage(message);
    }

    /**
     * Cleans up error messages to be user-friendly.
     */
    private String getCleanMessage(String message) {
        if (message == null) {
            return "An unexpected error occurred";
        }

        // Handle specific exception messages
        if (message.contains("No content to map due to end-of-input")) {
            return "Request body is required. Please provide a valid JSON payload.";
        }
        if (message.contains("JSON parse error")) {
            return "Invalid JSON format. Please check your request body syntax.";
        }
        if (message.contains("Required request body is missing")) {
            return "Request body is required. Please provide a valid JSON payload.";
        }
        if (message.contains("HttpMessageNotReadableException")) {
            return "Request body is required. Please provide a valid JSON payload.";
        }
        if (message.contains("MismatchedInputException")) {
            return "Request body is required. Please provide a valid JSON payload.";
        }

        return message;
    }

    /**
     * Returns a default message for common HTTP status codes.
     */
    private String getDefaultMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request was invalid or cannot be processed.";
            case UNAUTHORIZED -> "Authentication is required to access this resource.";
            case FORBIDDEN -> "You do not have permission to access this resource.";
            case NOT_FOUND -> "The requested resource was not found.";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not allowed for this endpoint.";
            case CONFLICT -> "The request conflicts with the current state of the resource.";
            case UNSUPPORTED_MEDIA_TYPE -> "The content type is not supported.";
            case INTERNAL_SERVER_ERROR -> "An internal server error occurred. Please try again later.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Please try again later.";
            default -> "An error occurred. Please try again.";
        };
    }
}
