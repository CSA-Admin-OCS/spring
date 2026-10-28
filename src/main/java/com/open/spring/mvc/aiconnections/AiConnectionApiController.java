package com.open.spring.mvc.aiconnections;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-connections")
@PreAuthorize("isAuthenticated()")
public class AiConnectionApiController {
    public static class KeyRequest {
        private String key;
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        // Deliberately no generated toString for a secret-bearing request.
    }
    private final AiConnectionService service;
    private final AiRequestGuard guard;
    public AiConnectionApiController(AiConnectionService service, AiRequestGuard guard) {
        this.service = service; this.guard = guard;
    }
    @GetMapping public ResponseEntity<AiConnectionService.Status> status(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(authentication));
    }
    @PutMapping(value = "/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiConnectionService.Status> connect(@PathVariable String provider,
            Authentication authentication, @RequestBody KeyRequest body, HttpServletRequest request) {
        guard.validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.connect(authentication, AiProvider.parse(provider), body.getKey()));
    }
    @DeleteMapping("/{provider}") public ResponseEntity<Void> disconnect(@PathVariable String provider,
            Authentication authentication, HttpServletRequest request) {
        guard.validate(request);
        service.disconnect(authentication, AiProvider.parse(provider));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
