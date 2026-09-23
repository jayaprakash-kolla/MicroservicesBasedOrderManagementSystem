package com.jayaprakash.oms.order.api;

import com.jayaprakash.oms.order.service.OrderNotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into one consistent JSON error shape.
 *
 * <p>Without this, a validation failure and a missing order return structurally
 * different bodies, which makes client code and debugging harder than it needs
 * to be.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles bean-validation failures on request bodies.
     *
     * <p>Input: the exception Spring raises when {@code @Valid} fails.
     * Processing: flattens the field errors into a field-to-message map.
     * Output: 400 with per-field detail.
     *
     * @param exception the validation failure
     * @return 400 with field-level messages
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {

        Map<String, String> fields = new HashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "VALIDATION_FAILED");
        body.put("fields", fields);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles lookups for orders that do not exist.
     *
     * <p>Input: the exception. Output: 404 with the message.
     *
     * @param exception the not-found failure
     * @return 404 with a description
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(OrderNotFoundException exception) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "ORDER_NOT_FOUND");
        body.put("message", exception.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
