package com.open.spring.security.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.open.spring.security.rbac.ControllerInventory.Handler;
import com.open.spring.security.rbac.ProbeContext.Caller;
import com.open.spring.security.rbac.Reading.Finding;

/** Checks how answers and annotations are read, on records written by hand: no application, no chain. */
class ReadingTest {

    private static Map<Caller, String> answers(String anonymous, String noRole, String... roleCallersThatReach) {
        Map<Caller, String> out = new EnumMap<>(Caller.class);
        for (Caller caller : Caller.values()) {
            out.put(caller, "403");
        }
        out.put(Caller.ANONYMOUS, anonymous);
        out.put(Caller.INVALID_TOKEN, anonymous);
        out.put(Caller.EXPIRED_TOKEN, anonymous);
        out.put(Caller.NO_ROLE, noRole);
        for (String name : roleCallersThatReach) {
            out.put(Caller.valueOf(name), "reached");
        }
        return out;
    }

    private static Probe.Probed probed(String request, Map<Caller, String> answers, List<String> guards, List<String> words) {
        String method = request.substring(0, request.indexOf(' '));
        String pattern = request.substring(method.length() + 1);
        Handler handler = new Handler(method, pattern, "api", "C", "h()", "C", "body", guards, List.of(), words);
        return new Probe.Probed(method, pattern, "api", "api", answers, List.of(handler));
    }

    // ---------------------------------------------------------------- the chain's level

    @Test
    void anyoneIsWhoeverReachesWithNoLogin() {
        assertEquals("anyone", Reading.level(answers("reached", "reached", "USER")));
    }

    @Test
    void signedInIsAnAccountWithNoRoleReaching() {
        assertEquals("signed_in", Reading.level(answers("401", "reached", "USER", "ADMIN")));
    }

    @Test
    void aListOfRolesIsTheRolesThatReach() {
        assertEquals("roles:ROLE_ADMIN,ROLE_TEACHER", Reading.level(answers("401", "403", "TEACHER", "ADMIN")));
    }

    @Test
    void nobodyIsNoKindOfCallerReaching() {
        assertEquals("nobody", Reading.level(answers("401", "403")));
    }

    @Test
    void anExpiredTokenIsNotARoleHolder() {
        Map<Caller, String> a = answers("401", "403", "ADMIN");
        a.put(Caller.EXPIRED_TOKEN, "reached");
        assertEquals("roles:ROLE_ADMIN", Reading.level(a));
    }

    @Test
    void whoALevelLetsThroughIsASetOfCallers() {
        assertTrue(Reading.allows("anyone").contains(Reading.ANONYMOUS));
        assertTrue(Reading.allows("signed_in").contains(Reading.NO_ROLE));
        assertTrue(!Reading.allows("signed_in").contains(Reading.ANONYMOUS));
        assertEquals(Set.of("ROLE_ADMIN"), Reading.allows("roles:ROLE_ADMIN"));
        assertEquals(Set.of(), Reading.allows("nobody"));
        assertEquals("signed_in", Reading.levelOf(Reading.allows("signed_in")));
        assertEquals("roles:ROLE_ADMIN,ROLE_TEACHER", Reading.levelOf(Set.of("ROLE_TEACHER", "ROLE_ADMIN")));
    }

    // ---------------------------------------------------------------- what an annotation says

    @Test
    void aSingleCallIsRead() {
        assertEquals(Set.of("ROLE_ADMIN"), Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('ADMIN'))")));
        assertEquals(Set.of("ROLE_ADMIN"), Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('ROLE_ADMIN'))")));
        assertEquals(Set.of("ROLE_ADMIN", "ROLE_TEACHER"), Reading.annotationAllows(List.of("method @PreAuthorize(hasAnyAuthority('ROLE_TEACHER', \"ROLE_ADMIN\"))")));
        assertEquals(Reading.allows("signed_in"), Reading.annotationAllows(List.of("class @PreAuthorize(isAuthenticated())")));
        assertEquals(Reading.allows("anyone"), Reading.annotationAllows(List.of("method @PreAuthorize(permitAll())")));
    }

    @Test
    void callsJoinedByOrOrAndAreCombined() {
        assertEquals(Set.of("ROLE_ADMIN", "ROLE_TEACHER"), Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('ADMIN') or hasRole('TEACHER'))")));
        assertEquals(Set.of("ROLE_ADMIN"), Reading.annotationAllows(List.of("method @PreAuthorize(hasAnyRole('ADMIN','TEACHER') and hasRole('ADMIN'))")));
    }

    @Test
    void theMethodsGuardDecidesOverTheClasss() {
        assertEquals(Set.of("ROLE_ADMIN"), Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('ADMIN'))", "class @PreAuthorize(isAuthenticated())")));
        assertEquals(Reading.allows("signed_in"), Reading.annotationAllows(List.of("class @PreAuthorize(isAuthenticated())")));
    }

    @Test
    void securedAndRolesAllowedAreRead() {
        assertEquals(Set.of("ROLE_ADMIN", "ROLE_TEACHER"), Reading.annotationAllows(List.of("method @Secured(ROLE_ADMIN,ROLE_TEACHER)")));
        assertEquals(Set.of("ROLE_ADMIN"), Reading.annotationAllows(List.of("method @RolesAllowed(ADMIN)")));
    }

    @Test
    void aGuardThatIsNotSimpleIsNotGuessedAt() {
        assertNull(Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('ADMIN') or #id == principal.id)")));
        assertNull(Reading.annotationAllows(List.of("method @PreAuthorize(@checker.allows(#id))")));
        assertNull(Reading.annotationAllows(List.of("method @PreAuthorize(hasRole('A') or hasRole('B') and hasRole('C'))")));
        assertNull(Reading.annotationAllows(List.of()));
    }

    // ---------------------------------------------------------------- where the layers differ

    private static List<String> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).toList();
    }

    @Test
    void anOpenRequestWithNothingElseGuardingItIsFound() {
        List<Finding> findings = Reading.findings(List.of(probed("GET /a", answers("reached", "reached", "USER"), List.of(), List.of())));
        assertEquals(List.of(Reading.OPEN_NOTHING_ELSE), kinds(findings));
    }

    @Test
    void anOpenRequestWithAnAnnotationOrRoleWordsIsSaidToBeGuardedElsewhere() {
        assertEquals(List.of(Reading.OPEN_CHECKED_ELSEWHERE, Reading.CHAIN_WIDER), kinds(Reading.findings(List.of(
                probed("GET /a", answers("reached", "reached", "USER"), List.of("method @PreAuthorize(hasRole('ADMIN'))"), List.of())))));
        assertEquals(List.of(Reading.OPEN_CHECKED_ELSEWHERE), kinds(Reading.findings(List.of(
                probed("GET /b", answers("reached", "reached", "USER"), List.of(), List.of("hasRole("))))));
    }

    @Test
    void sourceThatCouldNotBeFoundIsNotTakenForRoleWords() {
        assertEquals(List.of(Reading.OPEN_NOTHING_ELSE), kinds(Reading.findings(List.of(
                probed("GET /a", answers("reached", "reached", "USER"), List.of(), List.of("(source not found)"))))));
    }

    @Test
    void anAnnotationWiderThanTheChainIsFound() {
        List<Finding> findings = Reading.findings(List.of(probed("GET /a", answers("401", "403", "ADMIN"),
                List.of("method @PreAuthorize(hasAnyRole('ADMIN','TEACHER'))"), List.of())));
        assertEquals(List.of(Reading.ANNOTATION_WIDER), kinds(findings));
        assertTrue(findings.get(0).detail().contains("ROLE_TEACHER"));
    }

    @Test
    void aChainWiderThanTheAnnotationIsFound() {
        List<Finding> findings = Reading.findings(List.of(probed("GET /a", answers("401", "403", "USER", "ADMIN"),
                List.of("method @PreAuthorize(hasRole('ADMIN'))"), List.of())));
        assertEquals(List.of(Reading.CHAIN_WIDER), kinds(findings));
        assertTrue(findings.get(0).detail().contains("ROLE_USER"));
    }

    @Test
    void aChainAndAnAnnotationThatAgreeGiveNoFinding() {
        assertTrue(Reading.findings(List.of(probed("GET /a", answers("401", "403", "ADMIN"),
                List.of("method @PreAuthorize(hasRole('ADMIN'))"), List.of()))).isEmpty());
    }

    @Test
    void aRouteNobodyReachesIsFound() {
        assertEquals(List.of(Reading.NOBODY), kinds(Reading.findings(List.of(probed("GET /a", answers("401", "403"), List.of(), List.of())))));
    }

    @Test
    void aBadTokenThatIsNotTreatedLikeNoLoginIsFound() {
        Map<Caller, String> a = answers("401", "403", "ADMIN");
        a.put(Caller.EXPIRED_TOKEN, "reached");
        assertTrue(kinds(Reading.findings(List.of(probed("GET /a", a, List.of(), List.of())))).contains(Reading.BAD_TOKEN));
    }

    @Test
    void aGuardThatCannotBeReadIsListedForAPerson() {
        assertEquals(List.of(Reading.UNREADABLE_GUARD), kinds(Reading.findings(List.of(probed("GET /a", answers("401", "403", "ADMIN"),
                List.of("method @PreAuthorize(@checker.allows(#id))"), List.of())))));
    }
}
