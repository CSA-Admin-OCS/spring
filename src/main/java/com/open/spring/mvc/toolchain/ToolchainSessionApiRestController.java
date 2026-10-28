package com.open.spring.mvc.toolchain;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.toolchain.ToolchainStationRules.CheckResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Toolchain Trail one-time device pairing.
 * The game (JWT cookie) starts a station and polls it; the local agent has no
 * cookie, so it pairs with the one-time code and then uses a scoped bearer token.
 * See SecurityConfig for the access rules.
 */
@RestController
@RequestMapping("/api/toolchain")
public class ToolchainSessionApiRestController {
    private final ToolchainSessionService service;

    public ToolchainSessionApiRestController(ToolchainSessionService service) {
        this.service = service;
    }

    public record StartRequest(@NotBlank String stationId) {}
    public record StartResponse(
        UUID sessionId,
        String stationId,
        String pairingCode,
        Instant expiresAt
    ) {}

    public record PairRequest(@NotBlank String pairingCode) {}
    public record PairResponse(
        UUID sessionId,
        String stationId,
        String agentToken,
        Instant expiresAt
    ) {}

    public record CheckEvidence(String id, Integer exitCode, String output) {}
    /** stationId names the station the agent checked; results for any other station are refused. */
    public record ResultsRequest(String os, String stationId, List<CheckEvidence> checks) {}
    public record CheckRequest(@NotBlank String stationId) {}

    /**
     * The game's view of the current station check in a paired Toolchain Trail session.
     * The game requests a station check, the agent reports evidence, and the server
     * evaluates it using {@link ToolchainStationRules}. {@code checks} contains those
     * evaluated results, not the agent's raw evidence.
     */
    public record StationView(
        UUID sessionId,
        String stationId,
        Long requestedAt,
        String status,
        List<CheckResult> checks,
        Instant pairingExpiresAt,
        Instant updatedAt
    ) {}

    /** POST /api/toolchain/sessions - the signed-in game starts at a station and gets a pairing code for the trail. */
    @PostMapping("/sessions")
    public StartResponse start(
        Principal student,
        @RequestBody @Valid StartRequest request
    ) {
        // Security configuration requires an authenticated student here.
        return service.start(student.getName(), request.stationId());
    }

    /** POST /api/toolchain/pair - the local agent exchanges the code, once, for an agent token. */
    @PostMapping("/pair")
    public PairResponse pair(@RequestBody @Valid PairRequest request) {
        return service.pair(request.pairingCode());
    }

    /** POST /api/toolchain/sessions/{id}/check - the game reached a station; ask the paired agent to check it. */
    @PostMapping("/sessions/{sessionId}/check")
    public StationView requestCheck(
        Principal student,
        @PathVariable String sessionId,
        @RequestBody @Valid CheckRequest request
    ) {
        return service.requestCheck(student.getName(), sessionId, request.stationId());
    }

    /** GET /api/toolchain/sessions/{id}/next - the agent polls for the station the game wants checked. */
    @GetMapping("/sessions/{sessionId}/next")
    public StationView nextRequest(
        @PathVariable String sessionId,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return service.nextRequest(sessionId, authorization);
    }

    /** POST /api/toolchain/sessions/{id}/results - the agent's check results, sent with its bearer token. */
    @PostMapping("/sessions/{sessionId}/results")
    public StationView submitResults(
        @PathVariable String sessionId,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody ResultsRequest request
    ) {
        return service.submitResults(sessionId, authorization, request);
    }

    /** GET /api/toolchain/sessions/{id} - the game polls for verified progress. */
    @GetMapping("/sessions/{sessionId}")
    public StationView getSession(Principal student, @PathVariable String sessionId) {
        return service.getSession(student.getName(), sessionId);
    }

    /**
     * Answer errors here as JSON instead of letting them become an /error dispatch,
     * which this app's security chain turns into a redirect to the login page.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException error) {
        String message = error.getReason() == null ? "Request failed." : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", message));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
    public ResponseEntity<Map<String, String>> handleBadBody(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("error", "The request body is missing or malformed."));
    }
}
