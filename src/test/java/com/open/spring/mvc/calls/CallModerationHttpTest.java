package com.open.spring.mvc.calls;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.open.spring.mvc.person.Person;

/** Moderators (calls.policy.moderator-roles) pausing, removing and ending, through LiveKit's room service. */
class CallModerationHttpTest {

    private static final String ORIGIN = "http://localhost:4500";

    private CallTestStore store;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken ada;
    private UsernamePasswordAuthenticationToken teacher;
    private CallRoomEntity room;
    private String adaId;
    private String mentorId;

    @BeforeEach
    void setUp() {
        store = new CallTestStore();
        Person adaPerson = store.person(1, "ada", "ROLE_USER", "ROLE_STUDENT");
        Person mentorPerson = store.person(3, "grace", "ROLE_USER", "ROLE_STUDENT", "ROLE_MENTOR");
        ada = CallTestStore.login(adaPerson);
        teacher = CallTestStore.login(store.person(4, "mort", "ROLE_USER", "ROLE_TEACHER"));
        store.group(12, "Binary Beasts", adaPerson, mentorPerson);
        room = store.service.openFor(adaPerson, CallContextType.GROUP, "12", null);
        adaId = CallIdentity.of("ada", CallTestStore.LIVEKIT_SECRET);
        mentorId = CallIdentity.of("grace", CallTestStore.LIVEKIT_SECRET);
        mvc = MockMvcBuilders.standaloneSetup(store.controller())
                .setControllerAdvice(new CallExceptionHandler(store.properties))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    private ResultActions act(UsernamePasswordAuthenticationToken who, String path) throws Exception {
        return mvc.perform(post("/api/calls/" + room.getId() + path).principal(who)
                .header("Origin", ORIGIN).header("X-Origin", "client").contentType(MediaType.APPLICATION_JSON));
    }

    private void connected(String sid, String identity, String... roles) {
        store.presence.joined(room.getRoomName(), sid, new CallParticipant(identity, null, Set.of(roles)));
    }

    @Test
    void moderatorRemovesAndTheTokenIsThenRefused() throws Exception {
        act(teacher, "/participants/" + adaId + "/remove").andExpect(status().isNoContent());

        verify(store.liveKit).removeParticipant(room.getRoomName(), adaId);
        act(ada, "/token")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("REMOVED"));
    }

    @Test
    void nonModeratorCannotRemove() throws Exception {
        act(ada, "/participants/" + mentorId + "/remove")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_A_MODERATOR"));

        verify(store.liveKit, never()).removeParticipant(any(), any());
    }

    @Test
    void moderatorPausesAndResumes() throws Exception {
        connected("PA_ada", adaId, "ROLE_STUDENT");

        act(teacher, "/participants/" + adaId + "/pause").andExpect(status().isNoContent());
        verify(store.liveKit).updateParticipant(room.getRoomName(), adaId, CallGrants.PAUSED);

        act(teacher, "/participants/" + adaId + "/resume").andExpect(status().isNoContent());
        verify(store.liveKit).updateParticipant(room.getRoomName(), adaId, CallGrants.FULL);
    }

    @Test
    void aModeratorsPauseCarriesOverToANewToken() throws Exception {
        connected("PA_ada", adaId, "ROLE_STUDENT");
        act(teacher, "/participants/" + adaId + "/pause").andExpect(status().isNoContent());

        String token = act(ada, "/token").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String jwt = new com.fasterxml.jackson.databind.ObjectMapper().readTree(token).get("token").asText();
        java.util.Map<?, ?> video = (java.util.Map<?, ?>) io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        CallTestStore.LIVEKIT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build().parseSignedClaims(jwt).getPayload().get("video");
        org.junit.jupiter.api.Assertions.assertEquals(false, video.get("canPublish"));
        org.junit.jupiter.api.Assertions.assertEquals(false, video.get("canSubscribe"));
    }

    @Test
    void pausingSomeoneNotInTheCallIs404() throws Exception {
        act(teacher, "/participants/" + adaId + "/pause")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("PARTICIPANT_NOT_FOUND"));

        verify(store.liveKit, never()).updateParticipant(any(), any(), any());
    }

    @Test
    void resumingWhileTheRulesHoldIs409() throws Exception {
        connected("PA_ada", adaId, "ROLE_STUDENT");
        connected("PA_m", mentorId, "ROLE_STUDENT", "ROLE_MENTOR");
        act(teacher, "/participants/" + mentorId + "/pause").andExpect(status().isNoContent());

        act(teacher, "/participants/" + mentorId + "/resume")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("WAITING_FOR_SUPERVISOR"));

        verify(store.liveKit, never()).updateParticipant(room.getRoomName(), mentorId, CallGrants.FULL);
    }

    @Test
    void endDeletesTheRoom() throws Exception {
        act(teacher, "/end").andExpect(status().isNoContent());

        verify(store.liveKit).deleteRoom(room.getRoomName());
    }

    @Test
    void liveKitDownIs503ButTheRemovalStands() throws Exception {
        doThrow(new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE"))
                .when(store.liveKit).removeParticipant(any(), any());

        act(teacher, "/participants/" + adaId + "/remove")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.reason").value("MEDIA_UNAVAILABLE"));
        act(ada, "/token").andExpect(jsonPath("$.reason").value("REMOVED"));
    }
}
