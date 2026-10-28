package com.open.spring.mvc.gist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gist-connection")
@PreAuthorize("isAuthenticated()")
public class GistConnectionApiController {
    public static class TokenRequest {
        private String token;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        // No generated toString on a secret-bearing request.
    }
    private final GistConnectionService service;
    private final GistRequestGuard guard;
    public GistConnectionApiController(GistConnectionService service, GistRequestGuard guard) {
        this.service = service; this.guard = guard;
    }
    @GetMapping public ResponseEntity<GistConnectionService.Status> status(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(authentication));
    }
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GistConnectionService.Status> connect(Authentication authentication,
            @RequestBody TokenRequest body, HttpServletRequest request) {
        guard.validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.connect(authentication, body.getToken()));
    }
    @DeleteMapping public ResponseEntity<Void> disconnect(Authentication authentication, HttpServletRequest request) {
        guard.validate(request); service.disconnect(authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
