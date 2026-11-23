package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.open.spring.security.CookieFactory;
import com.open.spring.security.JwtAuthenticationEntryPoint;
import com.open.spring.security.JwtRequestFilter;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.MvcSecurityConfig;
import com.open.spring.security.RateLimitFilter;
import com.open.spring.security.SecurityConfig;

/** The approve and deny routes behind the real security filter chain. */
@WebMvcTest(controllers = PersonViewController.class)
@Import({SecurityConfig.class, MvcSecurityConfig.class, CookieFactory.class, JwtAuthenticationEntryPoint.class,
        JwtRequestFilter.class, RateLimitFilter.class})
@TestPropertySource(properties = "person.default-password=not-used")
class PersonCapstoneApprovalRouteTest {

    private static final String PROJECT = "/capstone/example/";
    private static final String OTHER_PROJECT = "/capstone/other/";
    private static final long MENTOR_ID = 7L;
    private static final long OTHER_MENTOR_ID = 8L;
    private static final long TEACHER_MENTOR_ID = 9L;

    @Autowired private MockMvc mvc;
    @MockitoBean private PersonDetailsService personDetailsService;
    @MockitoBean private JwtTokenUtil jwtTokenUtil;
    @MockitoBean private PasswordEncoder passwordEncoder;

    private final Map<Long, Person> people = new HashMap<>();

    @BeforeEach
    void setup() {
        people.put(MENTOR_ID, pendingMentor("mentor1"));
        people.put(OTHER_MENTOR_ID, pendingMentor("mentor2"));
        people.put(TEACHER_MENTOR_ID, pendingMentor("teacher1"));
        // Applies the decision to a real Person, so the state rules under test are the real ones.
        doAnswer(call -> {
            Person person = people.get(call.getArgument(0, Long.class));
            if (person == null) {
                throw new IllegalArgumentException("Person not found.");
            }
            if (call.getArgument(3, Boolean.class)) {
                person.approveCapstone(call.getArgument(1), call.getArgument(2));
            } else {
                person.denyCapstone(call.getArgument(1), call.getArgument(2));
            }
            return null;
        }).when(personDetailsService).decideCapstoneRequest(anyLong(), any(), anyString(), anyBoolean());
    }

    private Person pendingMentor(String uid) {
        Person person = new Person();
        person.setUid(uid);
        person.requestCapstone(PROJECT);
        person.requestCapstone(OTHER_PROJECT);
        return person;
    }

    private MockHttpSession signedIn(String uid, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role)).toList();
        User user = new User(uid, "not-used", authorities);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, authorities)));
        return session;
    }

    private MockHttpSession teacher() {
        return signedIn("teacher1", "ROLE_USER", "ROLE_TEACHER");
    }

    private MockHttpServletRequestBuilder decision(long personId, String action, String project) {
        return post("/mvc/person/capstones/" + personId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Origin", "client")
                .content("{\"project\":\"" + project + "\"}");
    }

    private String stateOf(long personId, String project) {
        return people.get(personId).capstoneStatus(project);
    }

    private void assertNothingDecided() {
        for (long id : List.of(MENTOR_ID, OTHER_MENTOR_ID, TEACHER_MENTOR_ID)) {
            assertEquals("pending", stateOf(id, PROJECT));
            assertEquals("pending", stateOf(id, OTHER_PROJECT));
        }
    }

    @Test
    void aTeacherCanApproveAndDeny() throws Exception {
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(teacher())).andExpect(status().isOk());
        mvc.perform(decision(MENTOR_ID, "deny", OTHER_PROJECT).session(teacher())).andExpect(status().isOk());
        assertEquals("approved", stateOf(MENTOR_ID, PROJECT));
        assertEquals("denied", stateOf(MENTOR_ID, OTHER_PROJECT));
        assertEquals("teacher1", people.get(MENTOR_ID).getCapstoneMentoring().get(PROJECT).get("decidedBy"));
    }

    @Test
    void anAdminCanApproveAndDeny() throws Exception {
        MockHttpSession admin = signedIn("admin1", "ROLE_USER", "ROLE_ADMIN");
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(admin)).andExpect(status().isOk());
        mvc.perform(decision(MENTOR_ID, "deny", OTHER_PROJECT).session(admin)).andExpect(status().isOk());
        assertEquals("approved", stateOf(MENTOR_ID, PROJECT));
        assertEquals("denied", stateOf(MENTOR_ID, OTHER_PROJECT));
    }

    @Test
    void mentorsAndStudentsAreRejected() throws Exception {
        MockHttpSession mentor = signedIn("mentor2", "ROLE_USER", "ROLE_MENTOR");
        MockHttpSession student = signedIn("student1", "ROLE_USER", "ROLE_STUDENT");
        for (MockHttpSession caller : List.of(mentor, student)) {
            mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(caller)).andExpect(status().isForbidden());
            mvc.perform(decision(MENTOR_ID, "deny", PROJECT).session(caller)).andExpect(status().isForbidden());
        }
        assertNothingDecided();
    }

    @Test
    void signedOutRequestsAreSentToLogin() throws Exception {
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT)).andExpect(status().is3xxRedirection());
        mvc.perform(decision(MENTOR_ID, "deny", PROJECT)).andExpect(status().is3xxRedirection());
        assertNothingDecided();
    }

    @Test
    void aMentorCannotApproveTheirOwnRequest() throws Exception {
        MockHttpSession ownMentor = signedIn("mentor1", "ROLE_USER", "ROLE_MENTOR");
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(ownMentor)).andExpect(status().isForbidden());
        assertNothingDecided();
    }

    @Test
    void aTeacherWhoIsAlsoAMentorCannotDecideTheirOwnRequest() throws Exception {
        MockHttpSession teacherMentor = signedIn("teacher1", "ROLE_USER", "ROLE_TEACHER", "ROLE_MENTOR");
        mvc.perform(decision(TEACHER_MENTOR_ID, "approve", PROJECT).session(teacherMentor))
                .andExpect(status().isConflict());
        mvc.perform(decision(TEACHER_MENTOR_ID, "deny", PROJECT).session(teacherMentor))
                .andExpect(status().isConflict());
        assertNothingDecided();
    }

    @Test
    void aMissingPersonIsRejected() throws Exception {
        mvc.perform(decision(999L, "approve", PROJECT).session(teacher())).andExpect(status().isBadRequest());
        assertNothingDecided();
    }

    @Test
    void anInvalidProjectIsRejected() throws Exception {
        for (String project : List.of("/other/page/", "https://evil.test/capstone/a/", "/capstone/UPPER/", "")) {
            mvc.perform(decision(MENTOR_ID, "approve", project).session(teacher())).andExpect(status().isBadRequest());
        }
        assertNothingDecided();
    }

    @Test
    void aRequestThatIsNotPendingIsRejected() throws Exception {
        mvc.perform(decision(MENTOR_ID, "deny", PROJECT).session(teacher())).andExpect(status().isOk());
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(teacher())).andExpect(status().isConflict());
        assertEquals("denied", stateOf(MENTOR_ID, PROJECT));

        mvc.perform(decision(MENTOR_ID, "approve", OTHER_PROJECT).session(teacher())).andExpect(status().isOk());
        mvc.perform(decision(MENTOR_ID, "deny", OTHER_PROJECT).session(teacher())).andExpect(status().isConflict());
        assertEquals("approved", stateOf(MENTOR_ID, OTHER_PROJECT));

        mvc.perform(decision(MENTOR_ID, "approve", "/capstone/never-requested/").session(teacher()))
                .andExpect(status().isConflict());
        assertNull(stateOf(MENTOR_ID, "/capstone/never-requested/"));
    }

    @Test
    void aPlainCrossSiteFormCannotDecide() throws Exception {
        mvc.perform(post("/mvc/person/capstones/" + MENTOR_ID + "/approve").session(teacher())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("project", PROJECT))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        mvc.perform(post("/mvc/person/capstones/" + MENTOR_ID + "/approve").session(teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"project\":\"" + PROJECT + "\"}"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        assertNothingDecided();
    }

    @Test
    void aForeignOriginCannotDecideOrPassThePreflight() throws Exception {
        mvc.perform(options("/mvc/person/capstones/" + MENTOR_ID + "/approve")
                        .header("Origin", "https://evil.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-origin"))
                .andExpect(status().isForbidden());
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(teacher()).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        assertNothingDecided();
    }

    @Test
    void aDecisionChangesOnlyTheSelectedMentorAndProject() throws Exception {
        mvc.perform(decision(MENTOR_ID, "approve", PROJECT).session(teacher())).andExpect(status().isOk());

        assertEquals("approved", stateOf(MENTOR_ID, PROJECT));
        assertEquals("pending", stateOf(MENTOR_ID, OTHER_PROJECT));
        assertEquals("pending", stateOf(OTHER_MENTOR_ID, PROJECT));
        assertEquals("pending", stateOf(OTHER_MENTOR_ID, OTHER_PROJECT));
    }
}
