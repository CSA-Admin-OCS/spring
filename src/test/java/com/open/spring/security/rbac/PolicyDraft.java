package com.open.spring.security.rbac;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the application does today, written in the policy file's words: the draft a person finishes.
 *
 * <p>For each request, who can make it is what the filter chain lets through, narrowed by the guard annotation on the
 * handler when there is one. A check written inside a handler is not counted: it stays in the code. A request anyone
 * can make goes to {@code legacy_open}, never to {@code public}: only a person can say it is open on purpose.
 */
final class PolicyDraft {

    private PolicyDraft() {
    }

    /** The guard annotations of the handlers that answer a request. */
    private static Set<String> guards(Probe.Probed probed) {
        Set<String> out = new LinkedHashSet<>();
        probed.handlers().forEach(handler -> out.addAll(handler.guards()));
        return out;
    }

    /** True when a request's guard annotations are too complex to read, so its level cannot be worked out here. */
    static boolean unreadable(Probe.Probed probed) {
        Set<String> guards = guards(probed);
        return !guards.isEmpty() && Reading.annotationAllows(new ArrayList<>(guards)) == null;
    }

    /** Who can make the request today: the chain's answer, narrowed by the annotation. */
    static String effective(Probe.Probed probed) {
        Set<String> allowed = new TreeSet<>(Reading.allows(probed.level()));
        Set<String> guards = guards(probed);
        if (!guards.isEmpty()) {
            Set<String> annotation = Reading.annotationAllows(new ArrayList<>(guards));
            if (annotation != null) {
                allowed.retainAll(annotation);
            }
        }
        return Reading.levelOf(allowed);
    }

    private static final Comparator<String> BY_PATH_THEN_METHOD = Comparator
            .comparing((String request) -> request.substring(request.indexOf(' ') + 1))
            .thenComparing(request -> request);

    /** The draft file's text, with one request on each line so a change shows as one line. */
    static String json(List<Probe.Probed> probed, String owner) throws IOException {
        List<String> open = new ArrayList<>();
        List<String> signedIn = new ArrayList<>();
        Map<String, List<String>> byRoles = new TreeMap<>(Comparator.comparingInt((String roles) -> roles.split(",").length).thenComparing(roles -> roles));
        for (Probe.Probed p : probed) {
            String level = effective(p);
            switch (level) {
                case "anyone" -> open.add(p.request());
                case "signed_in" -> signedIn.add(p.request());
                case "nobody" -> throw new IllegalStateException(p.request() + " can be made by nobody: look at it by hand");
                default -> byRoles.computeIfAbsent(level.substring("roles:".length()), key -> new ArrayList<>()).add(p.request());
            }
        }
        open.sort(BY_PATH_THEN_METHOD);
        signedIn.sort(BY_PATH_THEN_METHOD);

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("role_names", Reading.ROLES);
        file.put("public", List.of());
        file.put("public_restricted", List.of());
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("owner", owner);
        group.put("requests", open);
        file.put("legacy_open", List.of(group));
        file.put("signed_in", signedIn);
        List<Map<String, Object>> roles = new ArrayList<>();
        byRoles.forEach((names, requests) -> {
            requests.sort(BY_PATH_THEN_METHOD);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("roles", List.of(names.split(",")));
            one.put("requests", requests);
            roles.add(one);
        });
        file.put("roles", roles);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return new ObjectMapper().writer(printer).writeValueAsString(file) + "\n";
    }
}
