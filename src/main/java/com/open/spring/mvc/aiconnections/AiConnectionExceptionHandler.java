package com.open.spring.mvc.aiconnections;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AiConnectionExceptionHandler {
    @ExceptionHandler(AiConnectionException.class)
    public ResponseEntity<Map<String, String>> handle(AiConnectionException e) {
        return ResponseEntity.status(e.getStatus()).cacheControl(CacheControl.noStore())
            .body(Map.of("code", e.getCode(), "error", e.getMessage()));
    }
}
