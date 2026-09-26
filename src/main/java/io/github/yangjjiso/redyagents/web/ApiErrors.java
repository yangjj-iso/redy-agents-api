package io.github.yangjjiso.redyagents.web;

import io.github.yangjjiso.redyagents.core.AgentException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> agentError(AgentException error) {
        return switch (error.reason()) {
            case INVALID -> response(HttpStatus.BAD_REQUEST, "invalid_request", error.getMessage());
            case NOT_FOUND -> response(HttpStatus.NOT_FOUND, "not_found", error.getMessage());
            case CONFLICT -> response(HttpStatus.CONFLICT, "conflict", error.getMessage());
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ignored) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "invalid request");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupported(HttpMediaTypeNotSupportedException ignored) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Content-Type must be application/json");
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
