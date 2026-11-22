package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** LiveKit's room service (Twirp over HTTP), against a stand-in server from the JDK. */
class LiveKitRoomsTest {

    private record Received(String path, String authorization, String contentType, JsonNode body) {}

    private static final String SECRET = "test-livekit-secret-0123456789abcdef";
    private final ObjectMapper json = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String answer = "{}";
    private CallsProperties properties;
    private LiveKitRooms rooms;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    json.readTree(body)));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        });
        server.start();
        properties = new CallsProperties();
        properties.getLivekit().setUrl("ws://127.0.0.1:" + server.getAddress().getPort());
        properties.getLivekit().setApiKey("APIkey123");
        properties.getLivekit().setApiSecret(SECRET);
        rooms = new LiveKitRooms(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static Claims claims(String authorization) {
        assertTrue(authorization.startsWith("Bearer "), authorization);
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
    }

    private Received only() {
        assertEquals(1, received.size());
        return received.get(0);
    }

    @Test
    void pausingSendsEveryPermissionAsFalse() throws Exception {
        rooms.updateParticipant("room-1", "ident-ada", CallGrants.PAUSED);

        Received request = only();
        assertEquals("/twirp/livekit.RoomService/UpdateParticipant", request.path());
        assertTrue(request.contentType().startsWith("application/json"));
        assertEquals(json.readTree("{\"room\":\"room-1\",\"identity\":\"ident-ada\",\"permission\":{"
                + "\"canSubscribe\":false,\"canPublish\":false,\"canPublishData\":false,\"canPublishSources\":[],"
                + "\"canUpdateMetadata\":false,\"hidden\":false}}"), request.body());
        Claims claims = claims(request.authorization());
        assertEquals("APIkey123", claims.getIssuer());
        Map<?, ?> video = (Map<?, ?>) claims.get("video");
        assertEquals("room-1", video.get("room"));
        assertEquals(true, video.get("roomAdmin"));
    }

    @Test
    void resumingNamesTheSourcesTheWayLiveKitsApiDoes() throws Exception {
        rooms.updateParticipant("room-1", "ident-ada", CallGrants.FULL);

        assertEquals(json.readTree("{\"canSubscribe\":true,\"canPublish\":true,\"canPublishData\":true,"
                + "\"canPublishSources\":[\"CAMERA\",\"MICROPHONE\",\"SCREEN_SHARE\"],"
                + "\"canUpdateMetadata\":false,\"hidden\":false}"), only().body().get("permission"));
    }

    @Test
    void removeSendsRemoveParticipant() throws Exception {
        rooms.removeParticipant("room-1", "ident-ada");

        Received request = only();
        assertEquals("/twirp/livekit.RoomService/RemoveParticipant", request.path());
        assertEquals(json.readTree("{\"room\":\"room-1\",\"identity\":\"ident-ada\"}"), request.body());
        assertEquals(true, ((Map<?, ?>) claims(request.authorization()).get("video")).get("roomAdmin"));
    }

    @Test
    void endingARoomNeedsRoomCreate() throws Exception {
        rooms.deleteRoom("room-1");

        Received request = only();
        assertEquals("/twirp/livekit.RoomService/DeleteRoom", request.path());
        assertEquals(json.readTree("{\"room\":\"room-1\"}"), request.body());
        assertEquals(true, ((Map<?, ?>) claims(request.authorization()).get("video")).get("roomCreate"));
    }

    @Test
    void aServerErrorIsMediaUnavailable() {
        status = 500;
        answer = "{\"code\":\"internal\",\"msg\":\"boom\"}";

        CallException refused = assertThrows(CallException.class,
                () -> rooms.updateParticipant("room-1", "ident-ada", CallGrants.PAUSED));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, refused.getStatus());
        assertEquals("MEDIA_UNAVAILABLE", refused.getReason());
    }

    @Test
    void anUnreachableServerIsMediaUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.getLivekit().setUrl("ws://127.0.0.1:" + closedPort);

        CallException refused = assertThrows(CallException.class,
                () -> new LiveKitRooms(properties).removeParticipant("room-1", "ident-ada"));
        assertEquals("MEDIA_UNAVAILABLE", refused.getReason());
    }

    @Test
    void someoneWhoAlreadyLeftIsNotAnError() {
        status = 404;
        answer = "{\"code\":\"not_found\",\"msg\":\"participant not found\"}";

        rooms.updateParticipant("room-1", "ident-ada", CallGrants.PAUSED);
        rooms.removeParticipant("room-1", "ident-ada");
        rooms.deleteRoom("room-1");
        assertEquals(3, received.size());
    }

    @Test
    void theApiIsTheSameHostOverHttp() {
        assertEquals("https://rtc.example.test", LiveKitRooms.apiBase("wss://rtc.example.test"));
        assertEquals("http://127.0.0.1:7880", LiveKitRooms.apiBase("ws://127.0.0.1:7880/"));
        assertEquals("https://rtc.example.test", LiveKitRooms.apiBase("https://rtc.example.test"));
    }
}
