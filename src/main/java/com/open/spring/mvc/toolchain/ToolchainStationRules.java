package com.open.spring.mvc.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Toolchain Trail stations and the checks that verify each one.
 *
 * The local agent (scripts/toolchain_agent.py in the pages repository) runs one
 * command per check id below and sends back its exit code and output. The rules
 * here decide pass/fail, so the browser and the agent never decide progress.
 * Check ids must match collect_evidence() in the agent.
 *
 * Patterns accept output from macOS, Windows and Linux (including WSL):
 * paths may use / or \, and git/ruby/python print the same version lines on all three.
 */
@Component
public class ToolchainStationRules {
    public static final String BOSS_STATION = "integration-summit";

    /** One piece of evidence from the agent. */
    public record Evidence(String id, Integer exitCode, String output) {}

    /** What the game shows for one check. */
    public record CheckResult(String id, String label, boolean passed, String detail) {}

    private record Rule(String id, String label, Predicate<String> passes, String fix) {}

    private static final Map<String, List<Rule>> STATIONS = new LinkedHashMap<>();

    static {
        STATIONS.put("terminal-town-gate", List.of(
            rule("working-folder", "Working folder", "^(?:/|[A-Za-z]:[\\\\/]|\\\\\\\\)\\S*",
                "Run the agent from a terminal so it can read your current folder."),
            rule("trail-folder", "Created toolchain-trail folder", "(?:^|[\\\\/])toolchain-trail[\\\\/]?\\s*$",
                "Create the folder with mkdir toolchain-trail in your home folder or the folder you run the agent from.")));
        STATIONS.put("compiler-canyon-forge", List.of(
            rule("python", "Python 3", "\\bPython\\s+3\\.\\d+", "Install Python 3 and make sure python3 (or py on Windows) runs."),
            rule("pip", "pip", "^pip\\s+\\d+(?:\\.\\d+)+", "Run: python3 -m ensurepip --upgrade"),
            rule("ruby", "Ruby", "\\bruby\\s+\\d+(?:\\.\\d+)+", "Install Ruby for your operating system, then open a new terminal."),
            // Bundler 2 prints "Bundler version 2.x"; Bundler 4+ prints only the number.
            rule("bundler", "Bundler", "^(?:Bundler version\\s+)?\\d+\\.\\d+", "Run: gem install bundler"),
            rule("rubygems", "RubyGems", "^\\d+\\.\\d+(?:\\.\\d+)?", "RubyGems comes with Ruby; reinstall Ruby if gem --version fails.")));
        STATIONS.put("editor-isle-tower", List.of(
            rule("editor", "VS Code or Cursor", "^\\d+\\.\\d+(?:\\.\\d+)?",
                "Install VS Code and add the code command to your PATH (Command Palette: Shell Command: Install 'code' command in PATH).")));
        STATIONS.put("git-village-hall", List.of(
            rule("git", "Git", "\\bgit version\\s+\\d+(?:\\.\\d+)+", "Install Git, then open a new terminal."),
            new Rule("git-name", "Git author name", output -> !output.isBlank(),
                "Run: git config --global user.name \"Your Name\""),
            new Rule("git-email", "Git author email",
                output -> output.contains("@") && !Pattern.compile("example\\.com|you@|your-email", Pattern.CASE_INSENSITIVE).matcher(output).find(),
                "Run: git config --global user.email \"you@yourdomain\" with your real email.")));
        STATIONS.put("github-gateway-arch", List.of(
            rule("github-remote", "GitHub remote", "github\\.com[:/]\\S+",
                "Run the agent inside a repository you cloned from GitHub."),
            rule("remote-branch", "Remote branch reachable", "\\b[0-9a-f]{40,64}\\s+refs/heads/\\S+",
                "Check your network, then run git ls-remote --heads origin inside the repository.")));
        STATIONS.put("build-bridge", List.of(
            rule("bundle-check", "Project dependencies installed",
                "the gemfile.s dependencies are satisfied|bundle complete!",
                "Inside the project run ./scripts/venv.sh (or bundle install), then run the agent again.")));
        STATIONS.put(BOSS_STATION, List.of(
            rule("recent-commit", "Recent commit", "^[0-9a-f]{7,40}\\s+\\S",
                "Make a commit in your repository, then run the agent inside it."),
            rule("push-dry-run", "Push dry run", "everything up.to.date|\\[new branch\\]|->\\s*\\S+",
                "Run git push --dry-run inside the repository and fix any error it shows.")));
    }

    private static Rule rule(String id, String label, String regex, String fix) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        return new Rule(id, label, output -> pattern.matcher(output).find(), fix);
    }

    /** The teacher's stationRules.requireAccessible: unknown stations get a 400. */
    public void requireAccessible(String studentUid, String stationId) {
        if (stationId == null || !STATIONS.containsKey(stationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown station.");
        }
    }

    public boolean isKnown(String stationId) {
        return stationId != null && STATIONS.containsKey(stationId);
    }

    /** Checks every rule of the station; a check the agent did not send fails. */
    public List<CheckResult> evaluate(String stationId, List<Evidence> evidence) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            if (item != null && item.id() != null) {
                byId.putIfAbsent(item.id(), item);
            }
        }
        List<CheckResult> results = new ArrayList<>();
        for (Rule rule : STATIONS.getOrDefault(stationId, List.of())) {
            Evidence item = byId.get(rule.id());
            if (item == null) {
                results.add(new CheckResult(rule.id(), rule.label(), false, "The agent did not send this check. Update the agent and run it again."));
                continue;
            }
            String output = item.output() == null ? "" : item.output().strip();
            boolean passed = Integer.valueOf(0).equals(item.exitCode()) && rule.passes().test(output);
            results.add(new CheckResult(rule.id(), rule.label(), passed, passed ? "" : rule.fix()));
        }
        return results;
    }
}
