package com.open.spring.mvc.gist;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GistExceptionHandler {
    @ExceptionHandler(GistException.class)
    public ResponseEntity<Map<String, String>> handle(GistException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
            .body(Map.of("code", exception.getCode(), "error", exception.getMessage()));
    }
}
