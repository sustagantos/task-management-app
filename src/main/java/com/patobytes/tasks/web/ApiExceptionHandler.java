package com.patobytes.tasks.web;

import com.patobytes.tasks.task.TaskNotFound;
import com.patobytes.tasks.task.TaskRuleViolation;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the task rules into HTTP the SPA can act on.
 *
 * <p>The messages are written to be shown to a person, because they will be -
 * "this task still has open subtasks" is the whole explanation the user needs,
 * and a generic 409 would send them hunting.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String message) {}

    @ExceptionHandler(TaskNotFound.class)
    public ResponseEntity<ApiError> notFound(TaskNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("Task not found"));
    }

    @ExceptionHandler(TaskRuleViolation.class)
    public ResponseEntity<ApiError> conflict(TaskRuleViolation e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    /** Query-string arguments the controller rejects: bad date range, open status filter. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError(detail.isBlank() ? "Invalid request" : detail));
    }
}
