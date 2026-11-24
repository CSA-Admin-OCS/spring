package com.open.spring.security.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks {@code src/main/resources/data/permissions.json} against this application: the check to run whenever a
 * route, a rule in a security config or a guard annotation changes.
 *
 * <p>Run:  ./mvnw test -Dtest=PermissionsPolicyTest
 * <p>To write a fresh draft of the file from what the application does today (to compare, or to start again):
 * ./mvnw test -Dtest=PermissionsPolicyTest -Drbac.policy.draft=/a/folder/outside/the/repo/permissions.json
 */
class PermissionsPolicyTest {

    private static ProbeContext context;
    private static List<Probe.Probed> probed;

    @BeforeAll
    static void probeEverything() throws Exception {
        context = ProbeContext.real();
        probed = Probe.run(context, ControllerInventory.scan());
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private static PolicyFile.Policy policy() throws Exception {
        return PolicyFile.parse(Files.readString(Path.of(PolicyFile.PATH), StandardCharsets.UTF_8));
    }

    private static List<String> first(List<String> problems) {
        return problems.stream().limit(10).toList();
    }

    // ---------------------------------------------------------------- the file against the application

    @Test
    void everyRequestIsInTheFileAndTheFileListsNothingElse() throws Exception {
        Set<String> application = new TreeSet<>();
        probed.forEach(p -> application.add(p.request()));
        Set<String> file = new TreeSet<>(policy().entries().keySet());

        List<String> problems = new ArrayList<>();
        application.stream().filter(request -> !file.contains(request)).forEach(request -> problems.add(request + " is not in the file"));
        file.stream().filter(request -> !application.contains(request)).forEach(request -> problems.add(request + " is in the file and the application has no such request"));
        assertTrue(problems.isEmpty(), problems.size() + " problems: " + first(problems));
    }

    @Test
    void theFileSaysWhoCanMakeEachRequestToday() throws Exception {
        PolicyFile.Policy policy = policy();
        List<String> problems = new ArrayList<>();
        for (Probe.Probed p : probed) {
            String file = policy.levelOf(p.request());
            String today = PolicyDraft.effective(p);
            if (file != null && !file.equals(today)) {
                problems.add(p.request() + ": the file says " + file + ", the application lets " + today + " through");
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " requests where the file and the application differ: " + first(problems));
    }

    @Test
    void noGuardIsTooComplexToRead() {
        List<String> unreadable = probed.stream().filter(PolicyDraft::unreadable).map(Probe.Probed::request).toList();
        assertTrue(unreadable.isEmpty(), "a guard annotation cannot be read here, so say by hand who may: " + first(unreadable));
    }

    @Test
    void noRequestCanBeMadeByNobody() {
        List<String> nobody = probed.stream().filter(p -> PolicyDraft.effective(p).equals("nobody")).map(Probe.Probed::request).toList();
        assertTrue(nobody.isEmpty(), "no caller can make these: a rule and a guard disagree: " + first(nobody));
    }

    @Test
    void everyRoleTheRulesNameIsDeclaredSoItIsAsked() throws Exception {
        Set<String> named = new TreeSet<>();
        for (String file : List.of("SecurityConfig.java", "MvcSecurityConfig.java")) {
            String source = Files.readString(Path.of("src/main/java/com/open/spring/security", file), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"(ROLE_[A-Z_]+)\"").matcher(source);
            while (matcher.find()) {
                named.add(matcher.group(1));
            }
        }
        assertFalse(named.isEmpty());
        Set<String> declared = policy().roleNames();
        assertTrue(declared.containsAll(named), "a rule names a role the file does not declare: " + named + " against " + declared);
        assertEquals(new TreeSet<>(Reading.ROLES), new TreeSet<>(declared), "the probe asks as each declared role, so the two lists are one list");
    }

    @Test
    void theDraftOfWhatTheApplicationDoesPassesTheLoaderAndMatches() throws Exception {
        String draft = PolicyDraft.json(probed, "misc");
        PolicyFile.Policy parsed = PolicyFile.parse(draft);
        assertEquals(probed.size(), parsed.entries().size());
        probed.forEach(p -> assertEquals(PolicyDraft.effective(p), parsed.levelOf(p.request()), p.request()));
        assertEquals(draft, PolicyDraft.json(probed, "misc"), "the same application must give the same draft");
        String out = System.getProperty("rbac.policy.draft");
        if (out != null && !out.isBlank()) {
            Path file = Path.of(out).toAbsolutePath();
            assertFalse(file.startsWith(Path.of("").toAbsolutePath()), "write the draft outside the repository, then compare, not to " + file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, draft, StandardCharsets.UTF_8);
            System.out.println("RBAC DRAFT written to " + file);
        }
    }

    // ---------------------------------------------------------------- the loader's own rules

    private static final String GOOD = """
            {"role_names": ["ROLE_ADMIN", "ROLE_TEACHER"], "public": ["POST /authenticate"], "public_restricted": [],
             "legacy_open": [{"owner": "misc", "requests": ["GET /open"]}], "signed_in": ["GET /me"],
             "roles": [{"roles": ["ROLE_ADMIN"], "requests": ["DELETE /x/{id}"]},
                       {"roles": ["ROLE_TEACHER", "ROLE_ADMIN"], "requests": ["GET /staff"]}]}
            """;

    private static void refused(String from, String to, String words) {
        assertTrue(GOOD.contains(from), "the example does not contain " + from);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PolicyFile.parse(GOOD.replace(from, to)));
        assertTrue(error.getMessage().contains(words), error.getMessage());
    }

    @Test
    void aClearFileIsReadWithEachRequestsLevel() {
        PolicyFile.Policy policy = PolicyFile.parse(GOOD);
        Map<String, String> levels = new LinkedHashMap<>();
        policy.entries().forEach((request, entry) -> levels.put(request, entry.level()));
        assertEquals("anyone", levels.get("POST /authenticate"));
        assertEquals("anyone", levels.get("GET /open"));
        assertEquals("signed_in", levels.get("GET /me"));
        assertEquals("roles:ROLE_ADMIN", levels.get("DELETE /x/{id}"));
        assertEquals("roles:ROLE_ADMIN,ROLE_TEACHER", levels.get("GET /staff"));
        assertEquals(null, policy.levelOf("GET /nowhere"));
    }

    @Test
    void aRequestInTwoListsIsRefused() {
        refused("\"GET /me\"", "\"GET /open\"", "it can be in only one");
        refused("\"GET /staff\"", "\"DELETE /x/{id}\"", "it can be in only one");
        refused("[\"POST /authenticate\"]", "[\"POST /authenticate\", \"POST /authenticate\"]", "it can be in only one");
    }

    @Test
    void aRoleThatIsNotDeclaredIsRefused() {
        refused("\"roles\": [\"ROLE_ADMIN\"],", "\"roles\": [\"ROLE_ADMINISTRATOR\"],", "not in role_names");
        refused("\"role_names\": [\"ROLE_ADMIN\", \"ROLE_TEACHER\"]", "\"role_names\": [\"admin\"]", "not a role name");
        refused("\"role_names\": [\"ROLE_ADMIN\", \"ROLE_TEACHER\"]", "\"role_names\": []", "role_names is empty");
    }

    @Test
    void aLegacyOpenGroupNeedsAnOwnerAndRequests() {
        refused("\"owner\": \"misc\", ", "", "needs an owner");
        refused("\"owner\": \"misc\"", "\"owner\": \" \"", "needs an owner");
        refused("\"requests\": [\"GET /open\"]", "\"requests\": []", "lists no requests");
        refused("\"owner\": \"misc\"", "\"owner\": \"misc\", \"review_by\": \"2027-01-31\"", "unknown key");
    }

    @Test
    void aRoleGroupThatIsEmptyOrRepeatedIsRefused() {
        refused("\"roles\": [\"ROLE_ADMIN\"],", "\"roles\": [],", "needs a list of roles");
        refused("\"roles\": [\"ROLE_TEACHER\", \"ROLE_ADMIN\"]", "\"roles\": [\"ROLE_ADMIN\"]", "repeats the roles of an earlier group");
        refused("\"requests\": [\"GET /staff\"]", "\"requests\": []", "lists no requests");
    }

    @Test
    void anEntryThatIsNotAMethodAndAPathIsRefused() {
        refused("\"GET /me\"", "\"/me\"", "not \"METHOD /path\"");
        refused("\"GET /me\"", "\"get /me\"", "not \"METHOD /path\"");
        refused("\"GET /me\"", "\"ANY /me\"", "not \"METHOD /path\"");
        refused("\"GET /me\"", "7", "not a text");
    }

    @Test
    void aMissingOrUnknownKeyIsRefused() {
        refused("\"signed_in\": [\"GET /me\"],", "", "needs the key signed_in");
        refused("\"signed_in\"", "\"signedIn\"", "unknown key");
        refused("\"public_restricted\": []", "\"public_restricted\": {}", "must be a list");
        refused("\"public\": [\"POST /authenticate\"],", "\"public\": [\"POST /authenticate\"], \"public\": [],", "not valid JSON");
    }
}
