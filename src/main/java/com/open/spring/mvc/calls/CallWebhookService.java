package com.open.spring.mvc.calls;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a LiveKit webhook changes: who is connected (CallPresence), attendance, the room's state,
 * and then the safety rules (CallSupervisor, which runs on its own thread). Each step can run
 * twice without harm, since LiveKit retries. Rooms Spring didn't make are ignored.
 */
@Service
public class CallWebhookService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallRoomJpaRepository rooms;
    private final CallPresence presence;
    private final CallAttendanceService attendance;
    private final CallSupervisor supervisor;
    private final CallEvents events;

    public CallWebhookService(CallRoomJpaRepository rooms, CallPresence presence, CallAttendanceService attendance,
            CallSupervisor supervisor, CallEvents events) {
        this.rooms = rooms;
        this.presence = presence;
        this.attendance = attendance;
        this.supervisor = supervisor;
        this.events = events;
    }

    @Transactional
    public void handle(byte[] body) {
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (IOException e) {
            throw new CallException(HttpStatus.BAD_REQUEST, "invalid_body");
        }
        String roomName = event.path("room").path("name").asText();
        Optional<CallRoomEntity> room = roomName.isEmpty() ? Optional.empty() : rooms.findByRoomName(roomName);
        if (room.isEmpty()) {
            return;
        }
        JsonNode participant = event.path("participant");
        switch (event.path("event").asText()) {
            case "participant_joined" -> joined(room.get(), participant);
            case "participant_left" -> left(room.get(), participant);
            case "room_finished" -> finished(room.get());
            default -> {
                // Other events (room_started, tracks) change nothing Spring keeps.
            }
        }
    }

    private void joined(CallRoomEntity room, JsonNode participant) {
        String identity = participant.path("identity").asText();
        String sid = participant.path("sid").asText();
        if (identity.isEmpty() || sid.isEmpty()) {
            return;
        }
        CallParticipant who = new CallParticipant(identity, presence.uidOf(identity),
                rolesIn(participant.path("metadata").asText()));
        if (presence.joined(room.getRoomName(), sid, who)) {
            attendance.joined(room, who);
            if (room.getState() != CallState.LIVE) {
                room.setState(CallState.LIVE);
                room.setStartedAt(Instant.now());
                room.setEndedAt(null);
                rooms.save(room);
            }
        }
        supervisor.onPresenceChanged(room.getRoomName());
        events.live(room, presence.present(room.getRoomName()).size());
    }

    private void left(CallRoomEntity room, JsonNode participant) {
        String identity = participant.path("identity").asText();
        String sid = participant.path("sid").asText();
        if (identity.isEmpty() || sid.isEmpty()) {
            return;
        }
        presence.left(room.getRoomName(), sid);
        boolean stillConnected = presence.present(room.getRoomName()).stream()
                .anyMatch(other -> other.identity().equals(identity));
        if (!stillConnected) {
            attendance.left(room, identity);
        }
        supervisor.onPresenceChanged(room.getRoomName());
        events.live(room, presence.present(room.getRoomName()).size());
    }

    private void finished(CallRoomEntity room) {
        presence.clear(room.getRoomName());
        attendance.roomFinished(room);
        room.setState(CallState.IDLE);
        room.setEndedAt(Instant.now());
        rooms.save(room);
        supervisor.forget(room.getRoomName());
        events.ended(room);
    }

    // The roles the join token listed. A participant can't change their own metadata (canUpdateOwnMetadata is false).
    private static Set<String> rolesIn(String metadata) {
        Set<String> roles = new HashSet<>();
        try {
            JSON.readTree(metadata).path("roles").forEach(role -> roles.add(role.asText()));
        } catch (IOException e) {
            return Set.of();
        }
        return roles;
    }
}
