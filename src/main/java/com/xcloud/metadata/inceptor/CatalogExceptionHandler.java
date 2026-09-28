package com.xcloud.metadata.inceptor;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns database source failures into meaningful HTTP status codes.
 *
 * <p>Without this the caller only sees a generic 500, which cannot be told apart from a bug. The
 * frontend shows the {@code message} field, so the operator learns whether the statement was
 * abandoned on timeout (504) or whether the source is disabled/unreachable (503).
 */
@RestControllerAdvice(assignableTypes = ProcedureCatalogController.class)
public class CatalogExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogExceptionHandler.class);

    /** Request timeout reached; the database side may still be running the statement. */
    @ExceptionHandler(InceptorTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(InceptorTimeoutException exception) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body(exception.getMessage()));
    }

    /** Feature disabled, credentials or url missing, driver jar absent, connection refused. */
    @ExceptionHandler(InceptorUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(InceptorUnavailableException exception) {
        LOG.warn("数据库接入不可用：{}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(exception.getMessage()));
    }

    /** Procedure name not found in the metadata table, or its source text is empty. */
    @ExceptionHandler(ProcedureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ProcedureNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(exception.getMessage()));
    }

    /** No credentials, no source text: never echo anything but the already sanitized message. */
    private static Map<String, Object> body(String message) {
        return Map.of("message", message == null || message.isBlank() ? "数据库操作失败" : message);
    }
}
