package com.open.spring.mvc.calls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * Checks a LiveKit webhook. LiveKit signs each one with a JWT in the Authorization header (made
 * with the API key and secret, without "Bearer " in LiveKit 1.13) whose sha256 claim is the
 * base64 SHA-256 of the raw body. The token alone would accept any body, so both are checked.
 */
public final class LiveKitWebhookVerifier {

    private LiveKitWebhookVerifier() {
    }

    public static boolean verify(byte[] rawBody, String authorization, String apiKey, String apiSecret) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring("Bearer ".length()) : authorization;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(LiveKitTokens.signingKey(apiSecret))
                    .requireIssuer(apiKey)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
            if (!(claims.get("sha256") instanceof String claimedHash)) {
                return false;
            }
            byte[] bodyHash = Base64.getEncoder().encode(MessageDigest.getInstance("SHA-256").digest(rawBody));
            return MessageDigest.isEqual(claimedHash.getBytes(StandardCharsets.US_ASCII), bodyHash);
        } catch (JwtException | IllegalArgumentException | CallException e) {
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
