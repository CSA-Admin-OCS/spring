package com.open.spring.mvc.calls;

import static com.open.spring.mvc.calls.CallDecision.Outcome.ALLOW;
import static com.open.spring.mvc.calls.CallDecision.Outcome.DENY;
import static com.open.spring.mvc.calls.CallDecision.Outcome.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The safety rules, with the settings application.properties ships. */
class CallPolicyTest {

    private static CallParticipant person(String uid, String... roles) {
        return new CallParticipant("id-" + uid, uid, Set.of(roles));
    }

    private static final CallParticipant S1 = person("s1", "ROLE_USER", "ROLE_STUDENT");
    private static final CallParticipant S2 = person("s2", "ROLE_USER", "ROLE_STUDENT");
    private static final CallParticipant T = person("t", "ROLE_USER", "ROLE_TEACHER");
    private static final CallParticipant T2 = person("t2", "ROLE_USER", "ROLE_TEACHER");
    // A mentor account keeps the ROLE_STUDENT every account starts with.
    private static final CallParticipant M = person("m", "ROLE_USER", "ROLE_STUDENT", "ROLE_MENTOR");
    private static final CallParticipant TEACHER_WHO_IS_ALSO_MENTOR = person("tm", "ROLE_USER", "ROLE_TEACHER", "ROLE_MENTOR");
    // The seeded admin accounts hold every role, ROLE_STUDENT included.
    private static final CallParticipant ADMIN = person("admin", "ROLE_USER", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN");

    private CallPolicySettings settings;

    @BeforeEach
    void setUp() {
        settings = new CallPolicySettings();
        settings.setMinorRoles(List.of("ROLE_STUDENT"));
        settings.setSupervisorRoles(List.of("ROLE_TEACHER", "ROLE_ADMIN"));
        settings.setSupervisedRoles(List.of("ROLE_MENTOR"));
        settings.setModeratorRoles(List.of("ROLE_TEACHER", "ROLE_ADMIN"));
        settings.setOneAdultOneMinor(CallPolicySettings.OneAdultOneMinor.ALLOW_AND_LIST);
    }

    private CallDecision decide(List<CallParticipant> present, CallParticipant joining) {
        return CallPolicy.decide(settings, present, joining, Set.of());
    }

    // ---- supervised roles wait for a supervisor ----

    @Test
    void studentsAloneAreAllowedFullGrants() {
        assertEquals(new CallDecision(ALLOW, null, CallGrants.FULL, false), decide(List.of(S1), S2));
    }

    @Test
    void supervisedRoleWaitsWithoutASupervisor() {
        assertEquals(new CallDecision(WAIT, "WAITING_FOR_SUPERVISOR", CallGrants.PAUSED, false), decide(List.of(S1), M));
    }

    @Test
    void supervisedRoleJoinsWhenASupervisorIsPresent() {
        assertEquals(ALLOW, decide(List.of(S1, T), M).outcome());
    }

    @Test
    void supervisedRoleNeedsNoSupervisorWithoutMinors() {
        assertEquals(ALLOW, decide(List.of(T2), M).outcome());
        assertEquals(ALLOW, decide(List.of(), M).outcome());
    }

    @Test
    void supervisorRoleWinsOverSupervisedRole() {
        assertEquals(ALLOW, decide(List.of(S1), TEACHER_WHO_IS_ALSO_MENTOR).outcome());
    }

    @Test
    void supervisorRoleWinsOverMinorRole() {
        assertEquals(ALLOW, decide(List.of(ADMIN), M).outcome());
        assertEquals(Set.of(), CallPolicy.mustPause(settings, List.of(ADMIN, M)));
    }

    @Test
    void supervisedRoleWinsOverMinorRole() {
        assertEquals(Set.of(), CallPolicy.mustPause(settings, List.of(M)));
        assertFalse(decide(List.of(M), T).listed());
    }

    @Test
    void roleNamesComeFromSettings() {
        settings.setSupervisedRoles(List.of("ROLE_X"));

        assertEquals(ALLOW, decide(List.of(S1), M).outcome());
    }

    // ---- one adult and one minor ----

    @Test
    void oneAdultOneMinorAllowedAndListedByDefault() {
        CallDecision teacherJoins = decide(List.of(S1), T);
        assertEquals(ALLOW, teacherJoins.outcome());
        assertTrue(teacherJoins.listed());
        assertTrue(decide(List.of(T), S1).listed());
    }

    @Test
    void aSecondPersonEndsTheOneOnOne() {
        assertFalse(decide(List.of(S1, T), S2).listed());
        assertFalse(decide(List.of(S1, T), T2).listed());
    }

    @Test
    void oneAdultOneMinorCanRequireASecondAdult() {
        settings.setOneAdultOneMinor(CallPolicySettings.OneAdultOneMinor.REQUIRE_SECOND_ADULT);

        assertEquals("WAITING_FOR_SECOND_ADULT", decide(List.of(S1), T).reason());
        assertEquals("WAITING_FOR_SECOND_ADULT", decide(List.of(T), S1).reason());
        assertEquals(ALLOW, decide(List.of(T, T2), S1).outcome());
    }

    @Test
    void aRejoiningPersonIsCountedOnce() {
        assertTrue(decide(List.of(S1, T), T).listed());
    }

    // ---- removed means removed ----

    @Test
    void removedIdentityIsDenied() {
        assertEquals(new CallDecision(DENY, "REMOVED", CallGrants.PAUSED, false),
                CallPolicy.decide(settings, List.of(S1), S2, Set.of(S2.identity())));
    }

    // ---- pausing during a call ----

    @Test
    void mustPauseSupervisedWhenNoSupervisorAndMinors() {
        assertEquals(Set.of(M.identity()), CallPolicy.mustPause(settings, List.of(S1, M)));
    }

    @Test
    void mustPauseNobodyWhileASupervisorIsThere() {
        assertEquals(Set.of(), CallPolicy.mustPause(settings, List.of(S1, M, T)));
    }

    @Test
    void moderatorsComeFromSettings() {
        assertTrue(CallPolicy.canModerate(settings, T));
        assertFalse(CallPolicy.canModerate(settings, M));
        settings.setModeratorRoles(List.of("ROLE_ADMIN"));
        assertFalse(CallPolicy.canModerate(settings, T));
    }
}
