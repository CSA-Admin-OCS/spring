package com.open.spring.mvc.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.open.spring.mvc.toolchain.ToolchainStationRules.CheckResult;
import com.open.spring.mvc.toolchain.ToolchainStationRules.Evidence;

/** Output samples from macOS, Windows and Linux must all pass. */
class ToolchainStationRulesTest {
    private final ToolchainStationRules stations = new ToolchainStationRules();

    private boolean passes(String stationId, Evidence... evidence) {
        List<CheckResult> results = stations.evaluate(stationId, List.of(evidence));
        return !results.isEmpty() && results.stream().allMatch(CheckResult::passed);
    }

    @Test
    void terminalGateAcceptsMacWindowsAndLinuxPaths() {
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/Users/hope"),
                new Evidence("trail-folder", 0, "/Users/hope/toolchain-trail")));
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "C:\\Users\\Hope Doe"),
                new Evidence("trail-folder", 0, "C:\\Users\\Hope Doe\\toolchain-trail")));
        assertTrue(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope"),
                new Evidence("trail-folder", 0, "/home/hope/toolchain-trail/")));
        assertFalse(passes("terminal-town-gate",
                new Evidence("working-folder", 0, "/home/hope"),
                new Evidence("trail-folder", 1, "")));
    }

    @Test
    void compilerForgeAcceptsVersionOutputs() {
        assertTrue(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 3.12.4"),
                new Evidence("pip", 0, "pip 24.0 from C:\\Python312\\Lib\\site-packages\\pip (python 3.12)"),
                new Evidence("ruby", 0, "ruby 3.3.4 (2024-07-09 revision be1089c8ec) [x64-mingw-ucrt]"),
                new Evidence("bundler", 0, "Bundler version 2.5.16"),
                new Evidence("rubygems", 0, "3.5.16")));
        assertTrue(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 3.13.0"),
                new Evidence("pip", 0, "pip 24.2 from /opt/homebrew/lib/python3.13/site-packages/pip (python 3.13)"),
                new Evidence("ruby", 0, "ruby 3.4.1 (2024-12-25 revision 48d4efcb85) +PRISM [arm64-darwin24]"),
                new Evidence("bundler", 0, "4.0.1"),
                new Evidence("rubygems", 0, "3.6.2")));
        assertFalse(passes("compiler-canyon-forge",
                new Evidence("python", 0, "Python 2.7.18")));
    }

    @Test
    void gitVillageRejectsPlaceholderEmail() {
        assertFalse(passes("git-village-hall",
                new Evidence("git", 0, "git version 2.43.0"),
                new Evidence("git-name", 0, "Hope"),
                new Evidence("git-email", 0, "you@example.com")));
    }

    @Test
    void gatewayAndSummitAcceptGitOutput() {
        assertTrue(passes("github-gateway-arch",
                new Evidence("github-remote", 0, "origin\thttps://github.com/hope/portfolio.git (fetch)"),
                new Evidence("remote-branch", 0, "3f786850e387550fdab836ed7e6dc881de23001b\trefs/heads/main")));
        assertTrue(passes("integration-summit",
                new Evidence("recent-commit", 0, "a1b2c3d Add toolchain notes"),
                new Evidence("push-dry-run", 0, "Everything up-to-date")));
        assertTrue(passes("build-bridge",
                new Evidence("bundle-check", 0, "The Gemfile's dependencies are satisfied")));
    }

    @Test
    void nonZeroExitCodeFailsEvenWithMatchingOutput() {
        assertFalse(passes("editor-isle-tower", new Evidence("editor", 1, "1.94.2")));
    }
}
