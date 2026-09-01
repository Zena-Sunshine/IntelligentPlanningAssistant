package com.voyageiq.business.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.voyageiq.business.service.IdempotencyConflictException;
import java.time.DateTimeException;

@RestControllerAdvice
public class ApiErrorAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
        FieldError field = error.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = field == null ? "请求参数不合法" : field.getField() + " " + field.getDefaultMessage();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException error) {
        return body(HttpStatus.valueOf(error.getStatusCode().value()), "REQUEST_REJECTED", error.getReason());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, Object>> idempotencyConflict(IdempotencyConflictException error) {
        return body(HttpStatus.CONFLICT, "REQUEST_REJECTED", error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        return body(HttpStatus.BAD_REQUEST, "BUSINESS_VALIDATION_FAILED", error.getMessage());
    }

    @ExceptionHandler(DateTimeException.class)
    ResponseEntity<Map<String, Object>> invalidDate(DateTimeException error) {
        return body(HttpStatus.BAD_REQUEST, "BUSINESS_VALIDATION_FAILED", "invalid calendar date");
    }

    @ExceptionHandler({IllegalStateException.class, ObjectOptimisticLockingFailureException.class})
    ResponseEntity<Map<String, Object>> conflict(Exception error) {
        return body(HttpStatus.CONFLICT, "BUSINESS_CONFLICT", error.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, Object>> forbidden(SecurityException error) {
        return body(HttpStatus.FORBIDDEN, "BUSINESS_FORBIDDEN", error.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now(), "status", status.value(), "code", code,
                "message", message == null ? status.getReasonPhrase() : message));
    }
}
