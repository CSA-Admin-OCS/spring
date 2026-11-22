package com.open.spring.mvc.calls;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;

/**
 * What a moderator (a role in calls.policy.moderator-roles) can do in a call: pause or resume
 * someone, remove them, or end the call, through LiveKit's room service. LiveKit can't take back
 * a join token, so a removal is kept in CallPresence first, and the person gets no new token.
 */
@Service
public class CallModerationService {

    private final CallRoomService rooms;
    private final CallPresence presence;
    private final LiveKitRooms liveKit;
    private final CallSupervisor supervisor;
    private final CallsProperties properties;
    private final CallPolicySettings policy;

    public CallModerationService(CallRoomService rooms, CallPresence presence, LiveKitRooms liveKit,
            CallSupervisor supervisor, CallsProperties properties, CallPolicySettings policy) {
        this.rooms = rooms;
        this.presence = presence;
        this.liveKit = liveKit;
        this.supervisor = supervisor;
        this.properties = properties;
        this.policy = policy;
    }

    public void pause(Person moderator, long callId, String identity) {
        CallRoomEntity room = roomFor(moderator, callId);
        requireConnected(room, identity);
        supervisor.holdPaused(room.getRoomName(), identity);
        liveKit.updateParticipant(room.getRoomName(), identity, CallGrants.PAUSED);
    }

    /** 409 WAITING_FOR_SUPERVISOR when the safety rules still keep the person paused. */
    public void resume(Person moderator, long callId, String identity) {
        CallRoomEntity room = roomFor(moderator, callId);
        requireConnected(room, identity);
        if (!supervisor.releaseHold(room.getRoomName(), identity)) {
            throw new CallException(HttpStatus.CONFLICT, "WAITING_FOR_SUPERVISOR");
        }
        liveKit.updateParticipant(room.getRoomName(), identity, CallGrants.FULL);
    }

    public void remove(Person moderator, long callId, String identity) {
        CallRoomEntity room = roomFor(moderator, callId);
        presence.markRemoved(room.getRoomName(), identity); // first, so it holds even while LiveKit is down
        liveKit.removeParticipant(room.getRoomName(), identity);
    }

    public void end(Person moderator, long callId) {
        liveKit.deleteRoom(roomFor(moderator, callId).getRoomName());
    }

    private CallRoomEntity roomFor(Person moderator, long callId) {
        if (policy.getModeratorRoles().stream().noneMatch(moderator::hasRole)) {
            throw new CallException(HttpStatus.FORBIDDEN, "NOT_A_MODERATOR");
        }
        CallRoomEntity room = rooms.find(moderator, callId);
        if (!properties.getLivekit().isConfigured()) {
            throw new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
        return room;
    }

    private void requireConnected(CallRoomEntity room, String identity) {
        boolean connected = presence.present(room.getRoomName()).stream()
                .anyMatch(participant -> participant.identity().equals(identity));
        if (!connected) {
            throw new CallException(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
        }
    }
}
