package com.open.spring.security.rbac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.open.spring.security.rbac.ProbeContext.Caller;

/**
 * Turns what the probe saw, and what an annotation says, into one vocabulary: who may make a request.
 *
 * <p>A <i>level</i> is {@code anyone}, {@code signed_in} (any signed-in account, even one with no role),
 * {@code nobody}, or {@code roles:ROLE_A,ROLE_B}. Underneath a level is a set of who it lets through: "anonymous",
 * "no_role", and each role the application uses.
 */
final class Reading {

    static final String ANONYMOUS = "anonymous";
    static final String NO_ROLE = "no_role";
    /** Every role the application's code uses. */
    static final List<String> ROLES = List.of("ROLE_ADMIN", "ROLE_ASSIGNMENT_SYNC", "ROLE_MENTOR", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_USER");

    private static final Pattern CALL = Pattern.compile("^(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority)\\(\\s*((?:'[^']*'|\"[^\"]*\")(?:\\s*,\\s*(?:'[^']*'|\"[^\"]*\"))*)\\s*\\)$");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'|\"([^\"]*)\"");
    private static final Pattern GUARD = Pattern.compile("^(method|class) @(\\w+)\\((.*)\\)$");

    private Reading() {
    }

    private static boolean reached(Map<Caller, String> answers, Caller caller) {
        return "reached".equals(answers.get(caller));
    }

    /** What the filter chain lets through, from what the probe saw for each kind of caller. */
    static String level(Map<Caller, String> answers) {
        if (reached(answers, Caller.ANONYMOUS)) {
            return "anyone";
        }
        if (reached(answers, Caller.NO_ROLE)) {
            return "signed_in";
        }
        Set<String> roles = new TreeSet<>();
        for (Caller caller : Caller.values()) {
            if (caller.hasRole() && reached(answers, caller)) {
                roles.add(caller.role);
            }
        }
        return roles.isEmpty() ? "nobody" : "roles:" + String.join(",", roles);
    }

    /** Who a level lets through. */
    static Set<String> allows(String level) {
        Set<String> out = new TreeSet<>();
        switch (level) {
            case "anyone" -> {
                out.add(ANONYMOUS);
                out.add(NO_ROLE);
                out.addAll(ROLES);
            }
            case "signed_in" -> {
                out.add(NO_ROLE);
                out.addAll(ROLES);
            }
            case "nobody" -> { }
            default -> {
                if (!level.startsWith("roles:")) {
                    throw new IllegalArgumentException("not a level: " + level);
                }
                Collections.addAll(out, level.substring("roles:".length()).split(","));
            }
        }
        return out;
    }

    /** The level that lets exactly this set through. */
    static String levelOf(Set<String> allowed) {
        if (allowed.contains(ANONYMOUS)) {
            return "anyone";
        }
        if (allowed.contains(NO_ROLE)) {
            return "signed_in";
        }
        return allowed.isEmpty() ? "nobody" : "roles:" + String.join(",", new TreeSet<>(allowed));
    }

    // ---------------------------------------------------------------- what an annotation says

    /**
     * Who the guard annotations on a handler let through, when they are simple enough to read: a single call, or calls
     * joined by " or ", or by " and ". A guard on the method decides over one on the class. Returns null when there is
     * no such guard, or one that cannot be read here (it is then shown to a person, not guessed at).
     */
    static Set<String> annotationAllows(List<String> guards) {
        List<String> chosen = new ArrayList<>();
        for (String guard : guards) {
            if (guard.startsWith("method ")) {
                chosen.add(guard);
            }
        }
        if (chosen.isEmpty()) {
            chosen.addAll(guards);
        }
        Set<String> result = null;
        for (String guard : chosen) {
            Matcher matcher = GUARD.matcher(guard);
            if (!matcher.matches()) {
                return null;
            }
            String name = matcher.group(2);
            if (name.equals("PostAuthorize")) {
                continue;  // it runs after the handler and cannot be read as a level
            }
            Set<String> one = oneGuard(name, matcher.group(3));
            if (one == null) {
                return null;
            }
            result = result == null ? one : intersect(result, one);
        }
        return result;
    }

    private static Set<String> oneGuard(String name, String value) {
        switch (name) {
            case "PermitAll":
                return allows("anyone");
            case "DenyAll":
                return allows("nobody");
            case "Secured":
                return roles(value, false);
            case "RolesAllowed":
                return roles(value, true);
            case "PreAuthorize":
                return expression(value.trim());
            default:
                return null;
        }
    }

    private static Set<String> roles(String commaSeparated, boolean addPrefix) {
        Set<String> out = new TreeSet<>();
        for (String part : commaSeparated.split(",")) {
            String role = part.trim();
            if (!role.isEmpty()) {
                out.add(addPrefix && !role.startsWith("ROLE_") ? "ROLE_" + role : role);
            }
        }
        return out;
    }

    private static Set<String> expression(String expression) {
        boolean or = expression.contains(" or ");
        boolean and = expression.contains(" and ");
        if (or && and) {
            return null;
        }
        if (or || and) {
            Set<String> result = null;
            for (String part : expression.split(or ? " or " : " and ")) {
                Set<String> one = single(part.trim());
                if (one == null) {
                    return null;
                }
                result = result == null ? one : (or ? union(result, one) : intersect(result, one));
            }
            return result;
        }
        return single(expression);
    }

    private static Set<String> single(String expression) {
        switch (expression) {
            case "permitAll()", "permitAll":
                return allows("anyone");
            case "isAuthenticated()":
                return allows("signed_in");
            case "denyAll()":
                return allows("nobody");
            default:
                break;
        }
        Matcher call = CALL.matcher(expression);
        if (!call.matches()) {
            return null;
        }
        boolean role = call.group(1).equals("hasRole") || call.group(1).equals("hasAnyRole");
        Set<String> out = new TreeSet<>();
        Matcher quoted = QUOTED.matcher(call.group(2));
        while (quoted.find()) {
            String text = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            out.add(role && !text.startsWith("ROLE_") ? "ROLE_" + text : text);
        }
        return out;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.retainAll(b);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    // ---------------------------------------------------------------- where the layers differ

    record Finding(String kind, String request, String detail) {
    }

    static final String OPEN_NOTHING_ELSE = "open, and nothing else guards it";
    static final String OPEN_CHECKED_ELSEWHERE = "open at the chain, guarded by an annotation or by code";
    static final String ANNOTATION_WIDER = "the annotation allows who the chain refuses";
    static final String CHAIN_WIDER = "the chain lets through who the annotation refuses";
    static final String NOBODY = "no kind of caller gets through the chain";
    static final String BAD_TOKEN = "a bad token is not treated like no login";
    static final String UNREADABLE_GUARD = "a guard that is not simple enough to read here";

    /** Where the chain, the annotations and the role words in code disagree, one finding for each place. */
    static List<Finding> findings(List<Probe.Probed> probed) {
        List<Finding> out = new ArrayList<>();
        for (Probe.Probed p : probed) {
            String request = p.request();
            String level = p.level();
            Set<String> chainAllows = allows(level);
            Set<String> guards = new LinkedHashSet<>();
            Set<String> words = new TreeSet<>();
            for (ControllerInventory.Handler handler : p.handlers()) {
                guards.addAll(handler.guards());
                for (String word : handler.rolesInCode()) {
                    if (!word.startsWith("(")) {
                        words.add(word);
                    }
                }
            }
            if (level.equals("nobody")) {
                out.add(new Finding(NOBODY, request, "answers: " + p.answers()));
            }
            String anonymous = p.answers().get(Caller.ANONYMOUS);
            if (!p.answers().get(Caller.INVALID_TOKEN).equals(anonymous) || !p.answers().get(Caller.EXPIRED_TOKEN).equals(anonymous)) {
                out.add(new Finding(BAD_TOKEN, request, "none: " + anonymous + ", invalid: " + p.answers().get(Caller.INVALID_TOKEN)
                        + ", expired: " + p.answers().get(Caller.EXPIRED_TOKEN)));
            }
            if (level.equals("anyone")) {
                if (guards.isEmpty() && words.isEmpty()) {
                    out.add(new Finding(OPEN_NOTHING_ELSE, request, ""));
                } else {
                    out.add(new Finding(OPEN_CHECKED_ELSEWHERE, request, (guards.isEmpty() ? "" : guards.toString() + " ") + (words.isEmpty() ? "" : "role words in source: " + words)));
                }
            }
            if (!guards.isEmpty()) {
                Set<String> annotation = annotationAllows(new ArrayList<>(guards));
                if (annotation == null) {
                    out.add(new Finding(UNREADABLE_GUARD, request, guards.toString()));
                } else {
                    Set<String> annotationOnly = new TreeSet<>(annotation);
                    annotationOnly.removeAll(chainAllows);
                    Set<String> chainOnly = new TreeSet<>(chainAllows);
                    chainOnly.removeAll(annotation);
                    if (!annotationOnly.isEmpty()) {
                        out.add(new Finding(ANNOTATION_WIDER, request, "annotation also allows " + annotationOnly + "; chain: " + level));
                    }
                    if (!chainOnly.isEmpty()) {
                        out.add(new Finding(CHAIN_WIDER, request, "chain also lets through " + chainOnly + "; annotation: " + levelOf(annotation)));
                    }
                }
            }
        }
        return out;
    }
}
