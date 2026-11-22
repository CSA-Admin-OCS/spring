package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.person.Person;

/** LiveKit's webhooks: signed reports of who joined and left, which keep presence and attendance. */
class CallWebhookHttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private CallTestStore store;
    private MockMvc mvc;
    private CallRoomEntity room;

    @BeforeEach
    void setUp() {
        store = new CallTestStore();
        Person ada = store.person(1, "ada", "ROLE_USER", "ROLE_STUDENT");
        store.group(12, "Binary Beasts", ada);
        room = store.service.openFor(ada, CallContextType.GROUP, "12", null);
        mvc = MockMvcBuilders.standaloneSetup(new CallWebhookController(store.properties, store.webhooks))
                .setControllerAdvice(new CallExceptionHandler(store.properties))
                .build();
    }

    private String event(String type, String roomName, String sid, String identity, String... roles) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("event", type);
        body.put("id", "EV_" + sid);
        body.putObject("room").put("sid", "RM_1").put("name", roomName);
        if (sid != null) {
            ObjectNode participant = body.putObject("participant");
            participant.put("sid", sid);
            participant.put("identity", identity);
            participant.put("metadata", json.writeValueAsString(java.util.Map.of("roles", List.of(roles))));
        }
        return json.writeValueAsString(body);
    }

    private ResultActions send(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/calls/webhook").contentType("application/webhook+json").content(bytes)
                .header("Authorization", LiveKitWebhookVerifierTest.signature(bytes, "APIkey123", CallTestStore.LIVEKIT_SECRET)));
    }

    private ResultActions joined(String sid, String identity, String... roles) throws Exception {
        return send(event("participant_joined", room.getRoomName(), sid, identity, roles));
    }

    private ResultActions left(String sid, String identity) throws Exception {
        return send(event("participant_left", room.getRoomName(), sid, identity));
    }

    @Test
    void badSignatureIs401() throws Exception {
        byte[] body = event("participant_joined", room.getRoomName(), "PA_1", "id-ada", "ROLE_STUDENT")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/calls/webhook").contentType("application/webhook+json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("bad_signature"));
        mvc.perform(post("/api/calls/webhook").contentType("application/webhook+json").content(body)
                        .header("Authorization", LiveKitWebhookVerifierTest.signature(body, "APIkey123",
                                "another-livekit-secret-0123456789ab")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/calls/webhook").contentType("application/webhook+json")
                        .content(event("participant_joined", room.getRoomName(), "PA_2", "id-ada", "ROLE_TEACHER"))
                        .header("Authorization", LiveKitWebhookVerifierTest.signature(body, "APIkey123", CallTestStore.LIVEKIT_SECRET)))
                .andExpect(status().isUnauthorized());
        assertTrue(store.presence.present(room.getRoomName()).isEmpty());
    }

    @Test
    void participantJoinedUpdatesPresenceAndAttendance() throws Exception {
        store.presence.rememberToken("id-ada", "ada");

        joined("PA_1", "id-ada", "ROLE_STUDENT").andExpect(status().isOk());

        assertEquals(1, store.presence.present(room.getRoomName()).size());
        List<CallAttendanceEntity> rows = store.attendanceRows();
        assertEquals(1, rows.size());
        assertEquals(room.getId(), rows.get(0).getCallRoomId());
        assertEquals("ada", rows.get(0).getUid());
        assertEquals("id-ada", rows.get(0).getIdentity());
        assertEquals("ROLE_STUDENT", rows.get(0).getRoles());
        assertNotNull(rows.get(0).getJoinedAt());
        assertNull(rows.get(0).getLeftAt());
        assertEquals(CallState.LIVE, room.getState());
        assertNotNull(room.getStartedAt());
    }

    @Test
    void aRetriedJoinAddsOneAttendanceRow() throws Exception {
        joined("PA_1", "id-ada", "ROLE_STUDENT").andExpect(status().isOk());
        joined("PA_1", "id-ada", "ROLE_STUDENT").andExpect(status().isOk());
        joined("PA_2", "id-ada", "ROLE_STUDENT").andExpect(status().isOk());

        assertEquals(1, store.attendanceRows().size());
    }

    @Test
    void attendanceClosesWhenTheirLastConnectionLeaves() throws Exception {
        joined("PA_1", "id-ada", "ROLE_STUDENT");
        joined("PA_2", "id-ada", "ROLE_STUDENT");

        left("PA_1", "id-ada").andExpect(status().isOk());
        assertNull(store.attendanceRows().get(0).getLeftAt());

        left("PA_2", "id-ada").andExpect(status().isOk());
        assertNotNull(store.attendanceRows().get(0).getLeftAt());
        assertTrue(store.presence.present(room.getRoomName()).isEmpty());
    }

    @Test
    void roomFinishedEndsTheRoomAndClosesAttendance() throws Exception {
        joined("PA_1", "id-ada", "ROLE_STUDENT");

        send(event("room_finished", room.getRoomName(), null, null)).andExpect(status().isOk());

        assertEquals(CallState.IDLE, room.getState());
        assertNotNull(room.getEndedAt());
        assertNotNull(store.attendanceRows().get(0).getLeftAt());
        assertTrue(store.presence.present(room.getRoomName()).isEmpty());
    }

    @Test
    void aStudentJoiningPausesAMentorWithNoTeacher() throws Exception {
        joined("PA_m", "id-mentor", "ROLE_STUDENT", "ROLE_MENTOR");
        joined("PA_1", "id-ada", "ROLE_STUDENT");

        store.scheduler.runDue();

        verify(store.liveKit).updateParticipant(room.getRoomName(), "id-mentor", CallGrants.PAUSED);
    }

    @Test
    void aLiveKitFailureNeverHoldsUpTheWebhook() throws Exception {
        doThrow(new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE"))
                .when(store.liveKit).updateParticipant(any(), any(), any());

        joined("PA_m", "id-mentor", "ROLE_STUDENT", "ROLE_MENTOR").andExpect(status().isOk());
        joined("PA_1", "id-ada", "ROLE_STUDENT").andExpect(status().isOk());
        store.scheduler.runDue();

        // Each webhook's check tried the pause LiveKit refused; the failure stayed on the scheduler.
        verify(store.liveKit, atLeastOnce()).updateParticipant(room.getRoomName(), "id-mentor", CallGrants.PAUSED);
    }

    @Test
    void aRoomThatIsNotOursIsIgnored() throws Exception {
        send(event("participant_joined", "someone-elses-room", "PA_1", "id-ada", "ROLE_STUDENT")).andExpect(status().isOk());

        assertTrue(store.presence.present("someone-elses-room").isEmpty());
        assertTrue(store.attendanceRows().isEmpty());
    }

    @Test
    void disabledIs404() throws Exception {
        store.properties.setEnabled(false);

        joined("PA_1", "id-ada", "ROLE_STUDENT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
        assertTrue(store.presence.present(room.getRoomName()).isEmpty());
    }
}
