package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.person.Person;

/**
 * The HTTP contract of /api/calls: the off switch, the request guard, JSON field names and status
 * codes, through the controller and its error handler with the real service over the in-memory
 * store. Spring Security's filters are not part of a standalone MockMvc; SecurityConfig's default
 * /api/** rule (a signed-in person) is checked live after spring-boot:run.
 */
class CallApiHttpTest {

    private static final String ORIGIN = "http://localhost:4500";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
    private CallTestStore store;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken ada;
    private UsernamePasswordAuthenticationToken niko;
    private UsernamePasswordAuthenticationToken mentor;
    private UsernamePasswordAuthenticationToken teacher;

    @BeforeEach
    void setUp() {
        store = new CallTestStore();
        Person adaPerson = store.person(1, "ada", "ROLE_USER", "ROLE_STUDENT");
        Person mentorPerson = store.person(3, "grace", "ROLE_USER", "ROLE_STUDENT", "ROLE_MENTOR");
        ada = CallTestStore.login(adaPerson);
        niko = CallTestStore.login(store.person(2, "niko", "ROLE_USER", "ROLE_STUDENT"));
        mentor = CallTestStore.login(mentorPerson);
        teacher = CallTestStore.login(store.person(4, "mort", "ROLE_USER", "ROLE_TEACHER"));
        store.group(12, "Binary Beasts", adaPerson, mentorPerson);
        CallApiRestController controller = new CallApiRestController(
                store.properties, new CallRequestGuard(store.properties), store.service, store.tokens, store.people);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CallExceptionHandler(store.properties))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    /** What the Pages call page sends on every request. */
    private static MockHttpServletRequestBuilder fromPages(MockHttpServletRequestBuilder request) {
        return request.header("Origin", ORIGIN).header("X-Origin", "client").contentType(MediaType.APPLICATION_JSON);
    }

    private String openBody(String contextType, String contextKey, String purpose) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("contextType", contextType);
        body.put("contextKey", contextKey);
        if (purpose != null) {
            body.put("purpose", purpose);
        }
        return json.writeValueAsString(body);
    }

    private int openGroupRoom() throws Exception {
        String response = mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asInt();
    }

    // ---- the off switch and the guard ----

    @Test
    void disabledAnswers404() throws Exception {
        int id = openGroupRoom();
        store.properties.setEnabled(false);

        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
        mvc.perform(fromPages(get("/api/calls/" + id)).principal(ada))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
    }

    @Test
    void disabledAnswers404EvenToRequestsThatWouldBeRefused() throws Exception {
        store.properties.setEnabled(false);

        mvc.perform(get("/api/calls/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
        mvc.perform(post("/api/calls").principal(ada).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content("{"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("calls_disabled"));
    }

    @Test
    void unknownOriginIs403() throws Exception {
        String body = openBody("GROUP", "12", "standup");

        mvc.perform(post("/api/calls").principal(ada).header("Origin", "https://student.opencodingsociety.com")
                        .header("X-Origin", "client").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("origin_rejected"));
        mvc.perform(post("/api/calls").principal(ada).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("origin_rejected"));
        mvc.perform(get("/api/calls/1").principal(ada).header("X-Origin", "client"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("origin_rejected"));
        assertEquals(0, store.roomCount());
    }

    @Test
    void aWriteMustSendJson() throws Exception {
        mvc.perform(post("/api/calls").principal(ada).header("Origin", ORIGIN).header("X-Origin", "client")
                        .contentType(MediaType.TEXT_PLAIN).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("origin_rejected"));
        assertEquals(0, store.roomCount());
    }

    @Test
    void missingLoginIs401() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).content(openBody("GROUP", "12", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("unauthorized"));
        UsernamePasswordAuthenticationToken unknown = new UsernamePasswordAuthenticationToken("ghost", null, List.of());
        mvc.perform(fromPages(get("/api/calls/1")).principal(unknown))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("unauthorized"));
    }

    // ---- opening and reading a room ----

    @Test
    void memberOpensARoom() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.title").value("Binary Beasts"))
                .andExpect(jsonPath("$.contextType").value("GROUP"))
                .andExpect(jsonPath("$.contextKey").value("12"))
                .andExpect(jsonPath("$.purpose").value("standup"))
                .andExpect(jsonPath("$.roomName").doesNotExist())
                .andExpect(jsonPath("$.createdByUid").doesNotExist());
    }

    @Test
    void memberReadsTheRoom() throws Exception {
        int id = openGroupRoom();

        mvc.perform(fromPages(get("/api/calls/" + id)).principal(ada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.title").value("Binary Beasts"))
                .andExpect(jsonPath("$.contextType").value("GROUP"))
                .andExpect(jsonPath("$.purpose").value("standup"));
    }

    @Test
    void nonMemberGetIs403() throws Exception {
        int id = openGroupRoom();

        mvc.perform(fromPages(get("/api/calls/" + id)).principal(niko))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_A_MEMBER"));
        mvc.perform(fromPages(post("/api/calls")).principal(niko).content(openBody("GROUP", "12", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_A_MEMBER"));
    }

    @Test
    void unknownCallIs404() throws Exception {
        mvc.perform(fromPages(get("/api/calls/404")).principal(ada))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("CALL_NOT_FOUND"));
        mvc.perform(fromPages(get("/api/calls/99999999999999999999")).principal(ada))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("CALL_NOT_FOUND"));
    }

    @Test
    void unknownPurposeIs400() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "party")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_PURPOSE"));
    }

    @Test
    void unknownContextTypeIs400() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("CLASS", "12", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_CONTEXT_TYPE"));
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content("{\"contextKey\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_CONTEXT_TYPE"));
    }

    @Test
    void unknownContextIs404() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "999", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_CONTEXT"));
    }

    @Test
    void unreadableBodyIs400() throws Exception {
        mvc.perform(fromPages(post("/api/calls")).principal(ada).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("invalid_body"));
    }

    // ---- join tokens ----

    private org.springframework.test.web.servlet.ResultActions askForToken(int id,
            UsernamePasswordAuthenticationToken who) throws Exception {
        return mvc.perform(fromPages(post("/api/calls/" + id + "/token")).principal(who));
    }

    private static CallParticipant connected(String uid, String... roles) {
        return new CallParticipant(CallIdentity.of(uid, CallTestStore.LIVEKIT_SECRET), uid, java.util.Set.of(roles));
    }

    @Test
    void memberGetsAToken() throws Exception {
        int id = openGroupRoom();

        String response = askForToken(id, ada)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(CallTestStore.LIVEKIT_URL))
                .andExpect(jsonPath("$.expiresIn").value(600))
                .andExpect(jsonPath("$.canModerate").value(false))
                .andReturn().getResponse().getContentAsString();

        io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        CallTestStore.LIVEKIT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build().parseSignedClaims(json.readTree(response).get("token").asText()).getPayload();
        assertEquals("YcP_m_Mq4hQs7ZwUlJgNpI", claims.getSubject()); // ada's identity (CallIdentityTest)
        assertEquals("ada", claims.get("name"));
        assertEquals(store.room(id).getRoomName(), ((java.util.Map<?, ?>) claims.get("video")).get("room"));
        assertEquals("{\"roles\":[]}", claims.get("metadata"));
    }

    @Test
    void aTeacherCanModerate() throws Exception {
        int id = openGroupRoom();

        askForToken(id, teacher)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canModerate").value(true));
    }

    @Test
    void mentorWaitsForATeacher() throws Exception {
        int id = openGroupRoom();
        String room = store.room(id).getRoomName();
        store.presence.joined(room, "PA_ada", connected("ada", "ROLE_USER", "ROLE_STUDENT"));

        askForToken(id, mentor)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("WAITING_FOR_SUPERVISOR"));

        store.presence.joined(room, "PA_mort", connected("mort", "ROLE_USER", "ROLE_TEACHER"));
        askForToken(id, mentor).andExpect(status().isOk());
    }

    @Test
    void aRemovedPersonGetsNoNewToken() throws Exception {
        int id = openGroupRoom();
        store.presence.markRemoved(store.room(id).getRoomName(), CallIdentity.of("ada", CallTestStore.LIVEKIT_SECRET));

        askForToken(id, ada)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("REMOVED"));
    }

    @Test
    void nonMemberGetsNoToken() throws Exception {
        int id = openGroupRoom();

        askForToken(id, niko)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_A_MEMBER"));
    }

    @Test
    void unconfiguredLiveKitIs503() throws Exception {
        int id = openGroupRoom();
        store.properties.getLivekit().setUrl("");

        askForToken(id, ada)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.reason").value("MEDIA_UNAVAILABLE"));
    }

    @Test
    void aDatabaseFailureIs503NotA500() throws Exception {
        store.tableIsMissing();

        mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.reason").value("CALLS_UNAVAILABLE"));
        mvc.perform(fromPages(get("/api/calls/1")).principal(ada))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("CALLS_UNAVAILABLE"));
    }

    @Test
    void twoFirstOpensAtOnceShareOneRoom() throws Exception {
        CallRoomEntity winner = store.anotherRequestOpensFirst(CallContextType.GROUP, "12");

        String response = mvc.perform(fromPages(post("/api/calls")).principal(ada).content(openBody("GROUP", "12", "standup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("standup"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(winner.getId(), json.readTree(response).get("id").asLong());
        assertEquals(1, store.roomCount());
    }
}
