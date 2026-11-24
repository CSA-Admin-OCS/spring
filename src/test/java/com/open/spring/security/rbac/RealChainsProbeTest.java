package com.open.spring.security.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.security.rbac.ControllerInventory.Handler;
import com.open.spring.security.rbac.ProbeContext.Caller;
import com.open.spring.security.rbac.Reading.Finding;
import com.open.spring.security.rbac.SampleLabels.Label;

/**
 * Asks this application's two real filter chains about every request its controllers answer, as every kind of caller.
 *
 * <p>Run:  ./mvnw test -Dtest=RealChainsProbeTest
 * <p>To also write the full report (what each caller got for each request, the level of each, and the findings)
 * outside the repository: ./mvnw test -Dtest=RealChainsProbeTest -Drbac.probe.out=/a/folder/outside/the/repo/probe.json
 * It shows which requests anyone can make, so it is never committed or posted.
 */
class RealChainsProbeTest {

    private static ProbeContext context;
    private static List<Handler> inventory;
    private static List<Probe.Probed> probed;

    @BeforeAll
    static void probeEverything() throws Exception {
        context = ProbeContext.real();
        inventory = ControllerInventory.scan();
        probed = Probe.run(context, inventory);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private static Probe.Probed find(String request) {
        return probed.stream().filter(p -> p.request().equals(request)).findFirst()
                .orElseThrow(() -> new AssertionError(request + " is not in the inventory"));
    }

    @Test
    void theProbeReachesNoRealController() {
        assertEquals(1, context.controllerBeans(), "only the stand-in may answer");
    }

    @Test
    void everyRequestOfTheInventoryWasAskedAsEveryKindOfCaller() {
        assertTrue(probed.size() >= inventory.size() - 50, "asked " + probed.size() + " requests for an inventory of " + inventory.size());
        for (Probe.Probed p : probed) {
            assertEquals(Caller.values().length, p.answers().size(), p.request());
        }
    }

    @Test
    void everyAnswerIsAnAnswerAChainGives() {
        List<String> surprises = new ArrayList<>();
        for (Probe.Probed p : probed) {
            p.answers().forEach((caller, answer) -> {
                boolean expected = answer.equals("reached") || answer.equals("401") || answer.equals("403") || answer.equals("redirect:/login");
                if (!expected) {
                    surprises.add(p.request() + " as " + caller + ": " + answer);
                }
            });
        }
        assertTrue(surprises.isEmpty(), surprises.size() + " answers a filter chain should not give: " + surprises.stream().limit(8).toList());
    }

    @Test
    void theChainTheInventoryNamedIsTheChainThatTakesThePath() {
        List<String> wrong = new ArrayList<>();
        for (Probe.Probed p : probed) {
            if (!p.chainHint().equals("either") && !p.chainHint().equals(p.chain())) {
                wrong.add(p.request() + ": inventory says " + p.chainHint() + ", the filters say " + p.chain());
            }
            assertFalse(p.chain().equals("none"), p.request() + " is taken by no chain");
        }
        assertTrue(wrong.isEmpty(), wrong.toString());
    }

    @Test
    void aBadOrExpiredTokenIsAlwaysTreatedLikeNoLogin() {
        List<String> found = Reading.findings(probed).stream().filter(f -> f.kind().equals(Reading.BAD_TOKEN)).map(Finding::request).toList();
        assertTrue(found.isEmpty(), "a token that is bad or expired was not treated like no login for " + found);
    }

    @Test
    void theReadingAgreesWithEveryRouteLabelledByHand() {
        List<String> disagreements = new ArrayList<>();
        for (Label label : SampleLabels.ALL) {
            Probe.Probed p = find(label.request());
            if (!p.level().equals(label.who())) {
                disagreements.add(label.request() + ": labelled " + label.who() + " (" + label.because() + "), read as " + p.level() + " " + p.answers());
            }
        }
        assertTrue(disagreements.isEmpty(), disagreements.size() + " of " + SampleLabels.ALL.size() + " read differently:\n" + String.join("\n", disagreements));
    }

    @Test
    void theLabelledRoutesAreDistinctAndCoverBothChains() {
        assertEquals(SampleLabels.ALL.size(), SampleLabels.ALL.stream().map(Label::request).distinct().count());
        assertTrue(SampleLabels.ALL.stream().map(l -> find(l.request()).chain()).distinct().count() == 2);
        assertTrue(SampleLabels.ALL.size() >= 40);
        for (String level : List.of("anyone", "signed_in")) {
            assertTrue(SampleLabels.ALL.stream().anyMatch(l -> l.who().equals(level)), "no label reads as " + level);
        }
        assertTrue(SampleLabels.ALL.stream().anyMatch(l -> l.who().startsWith("roles:")));
    }

    @Test
    void askingAgainGivesTheSameAnswers() {
        List<Handler> some = inventory.stream().filter(h -> h.pattern().startsWith("/api/person")).toList();
        assertFalse(some.isEmpty());
        List<Probe.Probed> again = Probe.run(context, some);
        for (Probe.Probed p : again) {
            assertEquals(find(p.request()).answers(), p.answers(), p.request());
        }
    }

    @Test
    void theReportIsSummarisedAndMayBeWrittenOutsideTheRepository() throws Exception {
        Map<String, Long> levels = new TreeMap<>();
        probed.forEach(p -> levels.merge(p.level().startsWith("roles:") ? "roles" : p.level(), 1L, Long::sum));
        Map<String, Long> findings = new TreeMap<>();
        Reading.findings(probed).forEach(f -> findings.merge(f.kind(), 1L, Long::sum));
        System.out.println("RBAC PROBE requests=" + probed.size() + " levels=" + levels + " findings=" + findings);
        String out = System.getProperty("rbac.probe.out");
        if (out != null && !out.isBlank()) {
            Path file = Path.of(out).toAbsolutePath();
            assertFalse(file.startsWith(Path.of("").toAbsolutePath()), "write the report outside the repository, not to " + file);
            Files.createDirectories(file.getParent());
            Map<String, Object> report = new TreeMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Probe.Probed p : probed) {
                Map<String, Object> row = new TreeMap<>();
                row.put("request", p.request());
                row.put("chain", p.chain());
                row.put("level", p.level());
                row.put("answers", p.answers());
                row.put("handlers", p.handlers().stream().map(h -> h.controller() + "#" + h.handler()).toList());
                row.put("guards", p.handlers().stream().flatMap(h -> h.guards().stream()).distinct().toList());
                row.put("rolesInCode", p.handlers().stream().flatMap(h -> h.rolesInCode().stream()).distinct().toList());
                rows.add(row);
            }
            report.put("requests", rows);
            report.put("findings", Reading.findings(probed));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            System.out.println("RBAC PROBE written to " + file);
        }
    }
}
