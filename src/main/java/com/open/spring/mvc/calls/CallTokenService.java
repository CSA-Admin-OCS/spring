package com.open.spring.mvc.calls;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;

/**
 * Join tokens for a call: who may join comes from CallRoomService, the safety rules from
 * CallPolicy with who is connected now (CallPresence), and the token from LiveKitTokens.
 */
@Service
public class CallTokenService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** POST /api/calls/{id}/token's answer: where to connect, the token, and how long it lasts. */
    public record JoinToken(String url, String token, int expiresIn, boolean canModerate) {}

    private final CallRoomService rooms;
    private final CallPresence presence;
    private final CallsProperties properties;
    private final CallPolicySettings policy;

    public CallTokenService(CallRoomService rooms, CallPresence presence, CallsProperties properties,
            CallPolicySettings policy) {
        this.rooms = rooms;
        this.presence = presence;
        this.properties = properties;
        this.policy = policy;
    }

    /** A join token, or 403 with why the person waits, or 503 MEDIA_UNAVAILABLE while LiveKit isn't set up. */
    public JoinToken tokenFor(Person person, long callId) {
        CallRoomEntity room = rooms.find(person, callId);
        CallsProperties.LiveKit liveKit = properties.getLivekit();
        if (!liveKit.isConfigured()) {
            throw new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE");
        }
        List<String> roles = person.getRoles() == null ? List.of() : person.getRoles();
        CallParticipant joining = new CallParticipant(
                CallIdentity.of(person.getUid(), liveKit.getApiSecret()), person.getUid(), Set.copyOf(roles));
        CallDecision decision = CallPolicy.decide(policy, presence.present(room.getRoomName()), joining,
                presence.removed(room.getRoomName()));
        if (decision.outcome() != CallDecision.Outcome.ALLOW) {
            throw new CallException(HttpStatus.FORBIDDEN, decision.reason());
        }
        String token = LiveKitTokens.join(properties, joining.identity(), person.getName(), room.getRoomName(),
                decision.grants(), metadata(roles));
        return new JoinToken(liveKit.getUrl(), token, properties.getTokenTtlSeconds(),
                CallPolicy.canModerate(policy, joining));
    }

    // Only the roles the call rules name (a teacher, a mentor), which the page turns into labels.
    private String metadata(List<String> roles) {
        Set<String> named = new LinkedHashSet<>(policy.getSupervisorRoles());
        named.addAll(policy.getSupervisedRoles());
        named.addAll(policy.getModeratorRoles());
        try {
            return JSON.writeValueAsString(Map.of("roles", roles.stream().filter(named::contains).toList()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a call token's metadata", e);
        }
    }
}
