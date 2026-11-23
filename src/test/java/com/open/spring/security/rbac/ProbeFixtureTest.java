package com.open.spring.security.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.open.spring.security.rbac.ControllerInventory.Handler;
import com.open.spring.security.rbac.ProbeContext.Caller;

/** Checks the prober itself, on two small filter chains whose rules are written to have known answers. */
class ProbeFixtureTest {

    private static ProbeContext context;

    @BeforeAll
    static void build() {
        context = ProbeContext.fixture();
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private static Map<Caller, String> asked(String method, String pattern) {
        Handler handler = new Handler(method, pattern, "api", "Fixture", "h()", "Fixture", "body", List.of(), List.of(), List.of());
        return Probe.run(context, List.of(handler)).get(0).answers();
    }

    private static void only(Map<Caller, String> answers, String... reached) {
        List<String> expected = List.of(reached);
        for (Caller caller : Caller.values()) {
            assertEquals(expected.contains(caller.name()), "reached".equals(answers.get(caller)),
                    caller + " got " + answers.get(caller) + "; reached should be " + expected);
        }
    }

    @Test
    void anOpenRequestIsReachedByEveryKindOfCaller() {
        only(asked("GET", "/api/open/x"), "ANONYMOUS", "INVALID_TOKEN", "EXPIRED_TOKEN", "NO_ROLE", "USER", "STUDENT", "MENTOR", "TEACHER", "ADMIN", "ASSIGNMENT_SYNC");
    }

    @Test
    void aSignedInRequestIsReachedByAnyAccountEvenOneWithNoRole() {
        Map<Caller, String> answers = asked("GET", "/api/signed/x");
        only(answers, "NO_ROLE", "USER", "STUDENT", "MENTOR", "TEACHER", "ADMIN", "ASSIGNMENT_SYNC");
        assertEquals("401", answers.get(Caller.ANONYMOUS));
    }

    @Test
    void aBadOrExpiredTokenIsTreatedAsNoLogin() {
        Map<Caller, String> answers = asked("GET", "/api/signed/x");
        assertEquals("401", answers.get(Caller.INVALID_TOKEN));
        assertEquals("401", answers.get(Caller.EXPIRED_TOKEN));
    }

    @Test
    void aSignedInCallerWhoLacksTheRoleIsForbiddenNotAskedToLogIn() {
        Map<Caller, String> answers = asked("GET", "/api/staff/x");
        only(answers, "TEACHER", "ADMIN");
        assertEquals("403", answers.get(Caller.USER));
        assertEquals("403", answers.get(Caller.NO_ROLE));
        assertEquals("401", answers.get(Caller.ANONYMOUS));
    }

    @Test
    void oneRoleOnlyIsReachedByThatRoleOnly() {
        only(asked("GET", "/api/admin/x"), "ADMIN");
    }

    @Test
    void theDefaultRuleLetsInFourRolesAndNotTheOthers() {
        only(asked("GET", "/api/other/x"), "USER", "STUDENT", "TEACHER", "ADMIN");
    }

    @Test
    void theMvcChainSendsACallerWithNoLoginToTheLoginPage() {
        Map<Caller, String> answers = asked("GET", "/page/anything");
        assertEquals("redirect:/login", answers.get(Caller.ANONYMOUS));
        assertEquals("redirect:/login", answers.get(Caller.EXPIRED_TOKEN));
        only(answers, "NO_ROLE", "USER", "STUDENT", "MENTOR", "TEACHER", "ADMIN", "ASSIGNMENT_SYNC");
    }

    @Test
    void theMvcChainReadsTheSessionAndHoldsARoleToItsRule() {
        Map<Caller, String> answers = asked("GET", "/page/staff");
        only(answers, "TEACHER");
        assertEquals("403", answers.get(Caller.ADMIN));
    }

    @Test
    void anOpenPageIsOpenOnTheMvcChain() {
        only(asked("GET", "/page/open"), "ANONYMOUS", "INVALID_TOKEN", "EXPIRED_TOKEN", "NO_ROLE", "USER", "STUDENT", "MENTOR", "TEACHER", "ADMIN", "ASSIGNMENT_SYNC");
    }

    @Test
    void theChainIsFoundFromTheFiltersThatTakeThePath() {
        assertEquals("api", context.chainOf("/api/open/x"));
        assertEquals("mvc", context.chainOf("/page/open"));
    }

    @Test
    void theLevelIsReadFromWhoGotThrough() {
        List<Handler> handlers = List.of("/api/open/x", "/api/signed/x", "/api/staff/x", "/api/admin/x", "/api/other/x", "/page/open", "/page/staff", "/page/anything").stream()
                .map(path -> new Handler("GET", path, "api", "Fixture", "h()", "Fixture", "body", List.of(), List.of(), List.of())).toList();
        Map<String, String> levels = new java.util.TreeMap<>();
        Probe.run(context, handlers).forEach(p -> levels.put(p.pattern(), p.level()));
        assertEquals("anyone", levels.get("/api/open/x"));
        assertEquals("signed_in", levels.get("/api/signed/x"));
        assertEquals("roles:ROLE_ADMIN,ROLE_TEACHER", levels.get("/api/staff/x"));
        assertEquals("roles:ROLE_ADMIN", levels.get("/api/admin/x"));
        assertEquals("roles:ROLE_ADMIN,ROLE_STUDENT,ROLE_TEACHER,ROLE_USER", levels.get("/api/other/x"));
        assertEquals("anyone", levels.get("/page/open"));
        assertEquals("roles:ROLE_TEACHER", levels.get("/page/staff"));
        assertEquals("signed_in", levels.get("/page/anything"));
    }

    @Test
    void aMappingThatNamesNoMethodIsAskedWithEveryMethod() {
        Handler any = new Handler("ANY", "/api/open/x", "api", "Fixture", "h()", "Fixture", "body", List.of(), List.of(), List.of());
        assertEquals(List.of("DELETE", "GET", "PATCH", "POST", "PUT"), Probe.run(context, List.of(any)).stream().map(Probe.Probed::method).sorted().toList());
    }

    @Test
    void aVariableInAPatternIsFilledSoThePathCanBeAsked() {
        assertEquals("/api/u/1/files/1", ProbeContext.concrete("/api/u/{id}/files/{name:.+}"));
        assertEquals("/api/x/x", ProbeContext.concrete("/api/x/**"));
    }

    @Test
    void theContextHoldsOnlyTheStandInController() {
        assertEquals(1, context.controllerBeans(), "the probe must not reach real controllers");
        assertTrue(context.chainOf("/api/open/x").equals("api"));
    }
}
