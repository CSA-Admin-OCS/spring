package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** LiveKit's webhook signature: a JWT from the API key and secret whose sha256 claim is the body's hash. */
class LiveKitWebhookVerifierTest {

    static final String KEY = "APIkey123";
    static final String SECRET = "test-livekit-secret-0123456789abcdef";
    private static final byte[] BODY = "{\"event\":\"room_started\"}".getBytes(StandardCharsets.UTF_8);

    /** The Authorization header LiveKit sends with this body (no "Bearer " in LiveKit 1.13). */
    static String signature(byte[] body, String key, String secret) throws Exception {
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        return Jwts.builder()
                .issuer(key)
                .claim("sha256", hash)
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        assertTrue(LiveKitWebhookVerifier.verify(BODY, signature(BODY, KEY, SECRET), KEY, SECRET));
    }

    @Test
    void acceptsABearerPrefix() throws Exception {
        assertTrue(LiveKitWebhookVerifier.verify(BODY, "Bearer " + signature(BODY, KEY, SECRET), KEY, SECRET));
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        byte[] other = "{\"event\":\"room_finished\"}".getBytes(StandardCharsets.UTF_8);

        assertFalse(LiveKitWebhookVerifier.verify(other, signature(BODY, KEY, SECRET), KEY, SECRET));
    }

    @Test
    void rejectsAWrongSecret() throws Exception {
        assertFalse(LiveKitWebhookVerifier.verify(BODY, signature(BODY, KEY, "another-livekit-secret-0123456789ab"), KEY, SECRET));
    }

    @Test
    void rejectsAnotherApiKey() throws Exception {
        assertFalse(LiveKitWebhookVerifier.verify(BODY, signature(BODY, "APIother", SECRET), KEY, SECRET));
    }

    @Test
    void rejectsAMissingHeader() {
        assertFalse(LiveKitWebhookVerifier.verify(BODY, null, KEY, SECRET));
        assertFalse(LiveKitWebhookVerifier.verify(BODY, "", KEY, SECRET));
        assertFalse(LiveKitWebhookVerifier.verify(BODY, "not-a-jwt", KEY, SECRET));
    }

    @Test
    void rejectsATokenWithoutABodyHash() {
        String noHash = Jwts.builder().issuer(KEY).expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertFalse(LiveKitWebhookVerifier.verify(BODY, noHash, KEY, SECRET));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(BODY));
        String expired = Jwts.builder().issuer(KEY).claim("sha256", hash)
                .expiration(Date.from(Instant.now().minusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertFalse(LiveKitWebhookVerifier.verify(BODY, expired, KEY, SECRET));
    }
}
