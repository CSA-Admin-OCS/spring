package com.open.spring.mvc.calls;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

/**
 * LiveKit join tokens: a JWT signed HS256 with the API secret, in LiveKit's claim format (iss is
 * the API key, sub the identity, and a "video" grant map). LiveKit allows whatever a token leaves
 * out, so every grant is written, and every room power is refused by name.
 */
public final class LiveKitTokens {

    private static final Logger logger = LoggerFactory.getLogger(LiveKitTokens.class);

    private LiveKitTokens() {
    }

    public static String join(CallsProperties properties, String identity, String name, String roomName,
            CallGrants grants, String metadataJson) {
        CallsProperties.LiveKit liveKit = properties.getLivekit();
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", roomName);
        video.put("roomJoin", true);
        video.put("canPublish", grants.canPublish());
        video.put("canSubscribe", grants.canSubscribe());
        video.put("canPublishData", grants.canPublishData());
        video.put("canPublishSources", grants.sources());
        video.put("canUpdateOwnMetadata", false);
        video.put("roomAdmin", false);
        video.put("roomCreate", false);
        video.put("roomList", false);
        video.put("roomRecord", false);
        video.put("hidden", false);
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(liveKit.getApiKey())
                .subject(identity)
                .claim("name", name)
                .claim("metadata", metadataJson)
                .claim("video", video)
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getTokenTtlSeconds())))
                // LiveKit expects HS256; on its own jjwt would pick HS384 or HS512 for a longer secret.
                .signWith(signingKey(liveKit.getApiSecret()), Jwts.SIG.HS256)
                .compact();
    }

    static SecretKey signingKey(String apiSecret) {
        try {
            return Keys.hmacShaKeyFor(apiSecret.getBytes(StandardCharsets.UTF_8));
        } catch (WeakKeyException e) {
            logger.error("calls.livekit.api-secret is shorter than 32 bytes, so Spring can't sign LiveKit tokens");
            throw new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
    }
}
