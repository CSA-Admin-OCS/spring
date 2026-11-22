package com.open.spring.mvc.calls;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LiveKit's room service (Twirp: POST {server}/twirp/livekit.RoomService/{method} with a JSON body),
 * for what Spring does during a call: change a participant's permissions, remove them, end a room.
 * Any failure is 503 MEDIA_UNAVAILABLE (a LiveKit problem, not a bug); a 404 means the participant
 * or room is already gone, which is what Spring wanted anyway.
 */
@Component
public class LiveKitRooms {

    private static final Logger logger = LoggerFactory.getLogger(LiveKitRooms.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final ObjectMapper JSON = new ObjectMapper();
    // Join tokens name sources in lower case; the room service takes LiveKit's TrackSource names.
    private static final Map<String, String> SOURCE_NAMES = Map.of("camera", "CAMERA", "microphone", "MICROPHONE",
            "screen_share", "SCREEN_SHARE", "screen_share_audio", "SCREEN_SHARE_AUDIO");

    private final CallsProperties properties;
    private final OkHttpClient client;

    public LiveKitRooms(CallsProperties properties) {
        this.properties = properties;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(8))
                .build();
    }

    public void updateParticipant(String room, String identity, CallGrants grants) {
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("canSubscribe", grants.canSubscribe());
        permission.put("canPublish", grants.canPublish());
        permission.put("canPublishData", grants.canPublishData());
        permission.put("canPublishSources", grants.sources().stream().map(SOURCE_NAMES::get).toList());
        permission.put("canUpdateMetadata", false);
        permission.put("hidden", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        body.put("identity", identity);
        body.put("permission", permission);
        call("UpdateParticipant", room, false, body);
    }

    public void removeParticipant(String room, String identity) {
        call("RemoveParticipant", room, false, Map.of("room", room, "identity", identity));
    }

    public void deleteRoom(String room) {
        call("DeleteRoom", room, true, Map.of("room", room));
    }

    /** The room service's address: the URL browsers use, over http(s). */
    static String apiBase(String url) {
        String base = url.trim();
        if (base.startsWith("wss://")) {
            base = "https://" + base.substring("wss://".length());
        } else if (base.startsWith("ws://")) {
            base = "http://" + base.substring("ws://".length());
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private void call(String method, String room, boolean mayEndRoom, Map<String, Object> body) {
        CallsProperties.LiveKit liveKit = properties.getLivekit();
        if (!liveKit.isConfigured()) {
            throw new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
        Request request;
        try {
            request = new Request.Builder()
                    .url(apiBase(liveKit.getUrl()) + "/twirp/livekit.RoomService/" + method)
                    .header("Authorization", "Bearer " + LiveKitTokens.server(properties, room, mayEndRoom))
                    .post(RequestBody.create(JSON.writeValueAsBytes(body), JSON_TYPE))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a LiveKit request", e);
        }
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() || isAlreadyGone(response)) {
                return;
            }
            logger.warn("LiveKit {} answered {}", method, response.code());
        } catch (IOException e) {
            logger.warn("LiveKit {} failed: {}", method, e.getMessage());
        }
        throw new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
    }

    // Twirp answers a participant or room that is gone with 404 {"code":"not_found"}. Any other 404
    // (a wrong route, a proxy in front of LiveKit) means the request never reached the room service.
    private static boolean isAlreadyGone(Response response) throws IOException {
        if (response.code() != 404 || response.body() == null) {
            return false;
        }
        try {
            return "not_found".equals(JSON.readTree(response.body().string()).path("code").asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
