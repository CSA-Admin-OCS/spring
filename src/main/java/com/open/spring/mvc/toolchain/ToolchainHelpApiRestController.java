package com.open.spring.mvc.toolchain;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts help requests from signed-in CS Pathway students.
 * This is a teacher request queue, not a chat endpoint. Teachers read requests
 * on /mvc/cs-pathway/read.
 */
@RestController
@RequestMapping("/api/cs-pathway/help-requests")
public class ToolchainHelpApiRestController {
    /**
     * DTO for a student's request for teacher assistance.
     *
     * @param levelKey the level where the request originated
     * @param station the station within the level; may be null
     * @param message the student's help message
     */
    public record HelpRequestRecord(String levelKey, String station, String message) {
    }

    private final ToolchainHelpService helpRequestService;

    public ToolchainHelpApiRestController(ToolchainHelpService helpRequestService) {
        this.helpRequestService = helpRequestService;
    }

    /**
     * Records a student's request for help.
     *
     * The student identity comes from the authenticated principal. The service
     * validates the level and message and enforces the per-student cooldown.
     *
     * POST /api/cs-pathway/help-requests
     * Body: {"levelKey": "toolchain-trail", "station": "Git Village Hall", "message": "I need help..."}
     *
     * @param user the signed-in student
     * @param body the request details; station may be omitted
     * @return 200 when saved, 401 when signed out, 400 for invalid content, or
     *         429 while the student's cooldown is active
     */
    @PostMapping
    public ResponseEntity<?> sendHelpRequest(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody(required = false) HelpRequestRecord body) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to ask your teacher for help."));
        }
        try {
            ToolchainHelpEntity saved = helpRequestService.send(user.getUsername(), body);
            return ResponseEntity.ok(Map.of("sent", true, "createdAt", saved.getCreatedAt()));
        } catch (ToolchainHelpExceptionTooSoon error) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(error.getRetryAfterSeconds()))
                    .body(Map.of("error", error.getMessage(), "retryAfterSeconds", error.getRetryAfterSeconds()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }
}
