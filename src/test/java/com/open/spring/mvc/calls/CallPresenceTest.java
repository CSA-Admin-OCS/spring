package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Who is connected to each room, by LiveKit connection (sid). LiveKit may retry or reorder its
 * webhooks, and one person can connect twice (two tabs), so presence counts people by identity.
 */
class CallPresenceTest {

    private static final CallParticipant ADA = new CallParticipant("id-ada", "ada", Set.of("ROLE_STUDENT"));
    private static final CallParticipant NIKO = new CallParticipant("id-niko", "niko", Set.of("ROLE_STUDENT"));

    private final CallPresence presence = new CallPresence();

    private Set<String> present(String room) {
        return presence.present(room).stream().map(CallParticipant::identity).collect(Collectors.toSet());
    }

    @Test
    void joinedPeopleArePresent() {
        presence.joined("room-1", "PA_1", ADA);
        presence.joined("room-1", "PA_2", NIKO);

        assertEquals(Set.of("id-ada", "id-niko"), present("room-1"));
        assertEquals(Set.of(), present("room-2"));
    }

    @Test
    void duplicateJoinIsIdempotent() {
        presence.joined("room-1", "PA_1", ADA);
        presence.joined("room-1", "PA_1", ADA);

        assertEquals(1, presence.present("room-1").size());
    }

    @Test
    void leaveBeforeJoinIsIgnored() {
        presence.left("room-1", "PA_1");
        presence.joined("room-1", "PA_1", ADA); // the late join of a connection that already left

        assertEquals(Set.of(), present("room-1"));
    }

    @Test
    void sameIdentityTwoSidsCountsOnceUntilBothLeave() {
        presence.joined("room-1", "PA_1", ADA);
        presence.joined("room-1", "PA_2", ADA);
        assertEquals(1, presence.present("room-1").size());

        presence.left("room-1", "PA_1");
        assertEquals(Set.of("id-ada"), present("room-1"));

        presence.left("room-1", "PA_2");
        assertEquals(Set.of(), present("room-1"));
    }

    @Test
    void removalIsPerRoom() {
        presence.markRemoved("room-1", "id-ada");

        assertEquals(Set.of("id-ada"), presence.removed("room-1"));
        assertTrue(presence.removed("room-2").isEmpty());
    }
}
