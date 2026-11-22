package com.open.spring.mvc.calls;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * POST /api/calls/webhook: LiveKit's reports of who joined and left. LiveKit has no login cookie
 * (SecurityConfig permits this one route), so every request must carry LiveKit's signature over
 * its exact body. The answer is 200 at once; pausing anyone happens on CallSupervisor's thread.
 */
@RestController
public class CallWebhookController {

    /** LiveKit's events are a few kilobytes; anything far bigger isn't LiveKit. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final CallsProperties properties;
    private final CallWebhookService webhooks;

    public CallWebhookController(CallsProperties properties, CallWebhookService webhooks) {
        this.properties = properties;
        this.webhooks = webhooks;
    }

    @PostMapping("/api/calls/webhook")
    public ResponseEntity<Void> receive(@RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request) throws IOException {
        if (!properties.isEnabled()) {
            throw new CallException(HttpStatus.NOT_FOUND, "calls_disabled");
        }
        byte[] body = readAtMost(request, MAX_BODY_BYTES);
        CallsProperties.LiveKit liveKit = properties.getLivekit();
        if (!liveKit.isConfigured()
                || !LiveKitWebhookVerifier.verify(body, authorization, liveKit.getApiKey(), liveKit.getApiSecret())) {
            throw new CallException(HttpStatus.UNAUTHORIZED, "bad_signature");
        }
        webhooks.handle(body);
        return ResponseEntity.ok().build();
    }

    // Anyone can post here before the signature is checked, so read no more than LiveKit would send.
    private static byte[] readAtMost(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) {
            throw new CallException(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            throw new CallException(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
        }
        return body;
    }
}
