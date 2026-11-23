package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Join tokens in LiveKit's format, read back with the API secret the way LiveKit checks them. */
class LiveKitTokensTest {

    private static final String SECRET = "test-livekit-secret-0123456789abcdef";

    private CallsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CallsProperties();
        properties.getLivekit().setUrl("wss://rtc.example.test");
        properties.getLivekit().setApiKey("APIkey123");
        properties.getLivekit().setApiSecret(SECRET);
        properties.setTokenTtlSeconds(600);
    }

    private String join(CallGrants grants) {
        return LiveKitTokens.join(properties, "ident-ada", "Ada L", "room-uuid", grants, "{\"roles\":[]}");
    }

    private static Jws<Claims> parse(String token, String secret) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(token);
    }

    private static Map<?, ?> video(String token) {
        return (Map<?, ?>) parse(token, SECRET).getPayload().get("video");
    }

    @Test
    void claimsMatchLiveKitsFormat() {
        Claims claims = parse(join(CallGrants.FULL), SECRET).getPayload();

        assertEquals("APIkey123", claims.getIssuer());
        assertEquals("ident-ada", claims.getSubject());
        assertEquals("Ada L", claims.get("name"));
        assertEquals("{\"roles\":[]}", claims.get("metadata"));
        Map<?, ?> video = (Map<?, ?>) claims.get("video");
        assertEquals("room-uuid", video.get("room"));
        assertEquals(true, video.get("roomJoin"));
    }

    @Test
    void deniedGrantsAreExplicitFalse() {
        Map<?, ?> video = video(join(CallGrants.PAUSED));

        assertEquals(false, video.get("canPublish"));
        assertEquals(false, video.get("canSubscribe"));
        assertEquals(false, video.get("canPublishData"));
        assertEquals(List.of(), video.get("canPublishSources"));
    }

    @Test
    void sourcesListed() {
        Map<?, ?> video = video(join(CallGrants.FULL));

        assertEquals(true, video.get("canPublish"));
        assertEquals(true, video.get("canSubscribe"));
        assertEquals(true, video.get("canPublishData"));
        assertEquals(List.of("camera", "microphone", "screen_share"), video.get("canPublishSources"));
    }

    @Test
    void grantsNoRoomPowers() {
        Map<?, ?> video = video(join(CallGrants.FULL));

        for (String power : List.of("roomAdmin", "roomCreate", "roomList", "roomRecord", "canUpdateOwnMetadata", "hidden")) {
            assertEquals(false, video.get(power), power);
        }
    }

    @Test
    void livesSixHundredSeconds() {
        Claims claims = parse(join(CallGrants.FULL), SECRET).getPayload();

        assertEquals(600, (claims.getExpiration().getTime() - claims.getNotBefore().getTime()) / 1000);
    }

    @Test
    void signsWithHs256EvenForALongSecret() {
        String longSecret = "x".repeat(64);
        properties.getLivekit().setApiSecret(longSecret);

        assertEquals("HS256", parse(join(CallGrants.FULL), longSecret).getHeader().getAlgorithm());
    }

    @Test
    void aSecretShorterThan32BytesIsRefused() {
        properties.getLivekit().setApiSecret("secret");

        CallException refused = assertThrows(CallException.class, () -> join(CallGrants.FULL));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, refused.getStatus());
        assertEquals("MEDIA_UNAVAILABLE", refused.getReason());
    }
}
