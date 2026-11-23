package com.open.spring.security.rbac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.open.spring.security.rbac.ControllerInventory.Handler;
import com.open.spring.security.rbac.ProbeContext.Caller;

/** Every request in the inventory, asked of the real filter chains as every kind of caller. */
final class Probe {

    /** A mapping that names no method answers all of them: each is asked. */
    static final List<String> ANY_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /** One method and path, who it was asked as, what each got, and the handlers that answer it. */
    record Probed(String method, String pattern, String chainHint, String chain, Map<Caller, String> answers, List<Handler> handlers) {
        String request() {
            return method + " " + pattern;
        }

        String level() {
            return Reading.level(answers);
        }
    }

    private Probe() {
    }

    /** Each distinct method and path of the inventory, with the handlers that answer it. */
    static Map<String, List<Handler>> requests(List<Handler> inventory) {
        Map<String, List<Handler>> byRequest = new TreeMap<>();
        for (Handler handler : inventory) {
            List<String> methods = handler.method().equals("ANY") ? ANY_METHODS : List.of(handler.method());
            for (String method : methods) {
                byRequest.computeIfAbsent(method + " " + handler.pattern(), key -> new ArrayList<>()).add(handler);
            }
        }
        return byRequest;
    }

    static List<Probed> run(ProbeContext context, List<Handler> inventory) {
        List<Probed> out = new ArrayList<>();
        for (Map.Entry<String, List<Handler>> entry : requests(inventory).entrySet()) {
            String method = entry.getKey().substring(0, entry.getKey().indexOf(' '));
            String pattern = entry.getKey().substring(method.length() + 1);
            String path = ProbeContext.concrete(pattern);
            Map<Caller, String> answers = new EnumMap<>(Caller.class);
            for (Caller caller : Caller.values()) {
                answers.put(caller, context.ask(method, path, caller));
            }
            out.add(new Probed(method, pattern, entry.getValue().get(0).chain(), context.chainOf(path), new LinkedHashMap<>(answers), entry.getValue()));
        }
        out.sort(Comparator.comparing(Probed::pattern).thenComparing(Probed::method));
        return out;
    }
}
