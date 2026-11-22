package com.open.spring.mvc.calls;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every /api/calls refusal as {"reason": ...}, so the call page reads one field. It runs before the
 * app-wide advice and only for CallApiRestController. While calls.enabled is false every answer is
 * the same 404 calls_disabled, even to a request the guard or the JSON parser would refuse.
 */
@RestControllerAdvice(assignableTypes = CallApiRestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(CallExceptionHandler.class);

    private final CallsProperties properties;

    public CallExceptionHandler(CallsProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(CallException.class)
    public ResponseEntity<Map<String, String>> refused(CallException exception) {
        return answer(exception.getStatus(), exception.getReason());
    }

    /** A body that isn't JSON is refused before the controller (and its guard) runs; answer as the guard would. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> notJson(HttpMediaTypeNotSupportedException exception) {
        return answer(HttpStatus.FORBIDDEN, "origin_rejected");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException exception) {
        return answer(HttpStatus.BAD_REQUEST, "invalid_body");
    }

    /** An id too big for a long names no call. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return answer(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND");
    }

    /**
     * The database couldn't read or write call_room: the table couldn't be created, say, or SQLite
     * stayed busy. That isn't a bug in the request, so the page is told to try again, not given a 500.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseFailed(DataAccessException exception) {
        logger.warn("A calls request failed in the database: {}", exception.getMostSpecificCause().getMessage());
        return answer(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_UNAVAILABLE");
    }

    // A 503 means something Spring depends on is down, so the page is told when to try again.
    private ResponseEntity<Map<String, String>> answer(HttpStatus status, String reason) {
        if (!properties.isEnabled()) {
            return disabled();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return response.body(Map.of("reason", reason));
    }

    private static ResponseEntity<Map<String, String>> disabled() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "calls_disabled"));
    }
}
