package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Each mentor/project pair moves only through the agreed states. */
public class PersonCapstoneRulesTest {

    private static final String PROJECT = "/capstone/example/";

    private Person mentor;

    @BeforeEach
    public void setup() {
        mentor = new Person();
        mentor.setUid("mentor1");
    }

    private void assertRefused(Runnable change) {
        assertThrows(IllegalStateException.class, change::run);
    }

    @Test
    public void aPersonWithNoColumnValueHasNoChoices() {
        assertNull(mentor.getCapstoneMentoring());
        assertNull(mentor.capstoneStatus(PROJECT));
        assertEquals(0, mentor.resetCapstoneChoices());
    }

    @Test
    public void interestedCanBeRemovedSkippedOrRequested() {
        mentor.markCapstoneInterested(PROJECT);
        assertEquals("interested", mentor.capstoneStatus(PROJECT));
        mentor.clearCapstoneChoice(PROJECT);
        assertNull(mentor.capstoneStatus(PROJECT));
        mentor.markCapstoneInterested(PROJECT);
        mentor.skipCapstone(PROJECT);
        assertEquals("skipped", mentor.capstoneStatus(PROJECT));
        mentor.clearCapstoneChoice(PROJECT);
        mentor.markCapstoneInterested(PROJECT);
        mentor.requestCapstone(PROJECT);
        assertEquals("pending", mentor.capstoneStatus(PROJECT));
    }

    @Test
    public void aSkippedProjectOnlyAllowsUndo() {
        mentor.skipCapstone(PROJECT);
        assertRefused(() -> mentor.requestCapstone(PROJECT));
        assertRefused(() -> mentor.markCapstoneInterested(PROJECT));
        mentor.clearCapstoneChoice(PROJECT);
        assertNull(mentor.capstoneStatus(PROJECT));
    }

    @Test
    public void aPendingRequestOnlyAllowsCancelOrAStaffDecision() {
        mentor.requestCapstone(PROJECT);
        mentor.requestCapstone(PROJECT);
        assertEquals("pending", mentor.capstoneStatus(PROJECT));
        assertRefused(() -> mentor.markCapstoneInterested(PROJECT));
        assertRefused(() -> mentor.skipCapstone(PROJECT));
        assertRefused(() -> mentor.clearCapstoneChoice(PROJECT));
        mentor.cancelCapstoneRequest(PROJECT);
        assertNull(mentor.capstoneStatus(PROJECT));
    }

    @Test
    public void anApprovalIsFinalAndRecordsWhoDecided() {
        mentor.requestCapstone(PROJECT);
        mentor.approveCapstone(PROJECT, "teacher1");
        assertEquals("approved", mentor.capstoneStatus(PROJECT));
        assertEquals("teacher1", mentor.getCapstoneMentoring().get(PROJECT).get("decidedBy"));
        assertRefused(() -> mentor.requestCapstone(PROJECT));
        assertRefused(() -> mentor.skipCapstone(PROJECT));
        assertRefused(() -> mentor.cancelCapstoneRequest(PROJECT));
        assertRefused(() -> mentor.denyCapstone(PROJECT, "teacher1"));
    }

    @Test
    public void aDeniedProjectCanBeSavedSkippedOrRequestedAgainButNotApproved() {
        mentor.requestCapstone(PROJECT);
        mentor.denyCapstone(PROJECT, "teacher1");
        assertRefused(() -> mentor.approveCapstone(PROJECT, "teacher1"));
        assertRefused(() -> mentor.clearCapstoneChoice(PROJECT));
        mentor.requestCapstone(PROJECT);
        assertEquals("pending", mentor.capstoneStatus(PROJECT));
        assertNull(mentor.getCapstoneMentoring().get(PROJECT).get("decidedBy"));
    }

    @Test
    public void nobodyCanDecideTheirOwnRequest() {
        mentor.requestCapstone(PROJECT);
        assertRefused(() -> mentor.approveCapstone(PROJECT, "mentor1"));
        assertRefused(() -> mentor.denyCapstone(PROJECT, "mentor1"));
        assertRefused(() -> mentor.approveCapstone(PROJECT, null));
        assertEquals("pending", mentor.capstoneStatus(PROJECT));
    }

    @Test
    public void resetClearsOnlyInterestedAndSkipped() {
        mentor.markCapstoneInterested("/capstone/a/");
        mentor.skipCapstone("/capstone/b/");
        mentor.requestCapstone("/capstone/c/");
        mentor.requestCapstone("/capstone/d/");
        mentor.approveCapstone("/capstone/d/", "teacher1");
        mentor.requestCapstone("/capstone/e/");
        mentor.denyCapstone("/capstone/e/", "teacher1");

        assertEquals(2, mentor.resetCapstoneChoices());
        assertNull(mentor.capstoneStatus("/capstone/a/"));
        assertNull(mentor.capstoneStatus("/capstone/b/"));
        assertEquals("pending", mentor.capstoneStatus("/capstone/c/"));
        assertEquals("approved", mentor.capstoneStatus("/capstone/d/"));
        assertEquals("denied", mentor.capstoneStatus("/capstone/e/"));
    }

    @Test
    public void aMentorCanHoldManyPendingAndApprovedProjectsIndependently() {
        for (String project : List.of("/capstone/a/", "/capstone/b/", "/capstone/c/", "/capstone/d/")) {
            mentor.requestCapstone(project);
        }
        mentor.approveCapstone("/capstone/a/", "teacher1");
        mentor.denyCapstone("/capstone/b/", "teacher1");
        mentor.approveCapstone("/capstone/c/", "teacher1");

        assertEquals("approved", mentor.capstoneStatus("/capstone/a/"));
        assertEquals("denied", mentor.capstoneStatus("/capstone/b/"));
        assertEquals("approved", mentor.capstoneStatus("/capstone/c/"));
        assertEquals("pending", mentor.capstoneStatus("/capstone/d/"));
    }

    @Test
    public void repeatingAPendingRequestKeepsOneEntryAndAnApprovedProjectIsRefused() {
        mentor.requestCapstone(PROJECT);
        Object requestedAt = mentor.getCapstoneMentoring().get(PROJECT).get("updatedAt");
        mentor.requestCapstone(PROJECT);
        assertEquals(1, mentor.getCapstoneMentoring().size());
        assertEquals(requestedAt, mentor.getCapstoneMentoring().get(PROJECT).get("updatedAt"));
        mentor.approveCapstone(PROJECT, "teacher1");
        assertRefused(() -> mentor.requestCapstone(PROJECT));
    }

    @Test
    public void onlyCapstonePermalinksAreAccepted() {
        for (String bad : Arrays.asList(null, "", "/capstone/", "/capstone/UPPER/", "/capstone/a/b/", "/other/a/",
                "https://evil.example/capstone/a/", "/capstone/a", "/capstone/<x>/",
                "/capstone/" + "a".repeat(120) + "/")) {
            assertThrows(IllegalArgumentException.class, () -> mentor.requestCapstone(bad), String.valueOf(bad));
        }
    }

    @Test
    public void entriesAreCapped() {
        for (int i = 0; i < 200; i++) {
            mentor.skipCapstone("/capstone/p" + i + "/");
        }
        assertRefused(() -> mentor.skipCapstone("/capstone/one-more/"));
        mentor.clearCapstoneChoice("/capstone/p0/");
        mentor.skipCapstone("/capstone/one-more/");
    }

    @Test
    public void theFieldNeverAppearsInAPersonResponse() throws Exception {
        mentor.requestCapstone(PROJECT);
        mentor.approveCapstone(PROJECT, "teacher1");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(mentor);
        assertFalse(json.contains("capstone"), json);
        assertFalse(json.contains("teacher1"), json);
        assertFalse(mentor.toString().contains("teacher1"));
    }

    private Authentication signedIn(String uid, String... roles) {
        Authentication authentication = mock(Authentication.class);
        List<GrantedAuthority> granted = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role)).toList();
        when(authentication.getName()).thenReturn(uid);
        when(authentication.getAuthorities()).thenAnswer(call -> granted);
        return authentication;
    }

    @Test
    public void onlyMentorsReachTheMentorActions() {
        PersonCapstoneApiController controller = new PersonCapstoneApiController();
        ReflectionTestUtils.setField(controller, "personDetailsService", mock(PersonDetailsService.class));
        Authentication student = signedIn("student1", "ROLE_USER", "ROLE_STUDENT");
        Authentication teacher = signedIn("teacher1", "ROLE_USER", "ROLE_TEACHER");
        Map<String, String> body = Map.of("project", PROJECT);
        for (Authentication caller : List.of(student, teacher)) {
            ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                    () -> controller.request(body, caller));
            assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.reset(null)).getStatusCode());
        assertTrue(signedIn("m", "ROLE_MENTOR").getAuthorities().size() == 1);
    }
}
