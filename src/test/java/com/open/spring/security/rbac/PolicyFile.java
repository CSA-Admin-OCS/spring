package com.open.spring.security.rbac;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads {@code permissions.json}, the file that says who may make each request, and refuses one that is unclear.
 *
 * <p>A request is written "METHOD /path", with the path as Spring matches it ("GET /api/person/{id}"). It is in
 * exactly one list. A request in no list is one the file says nothing about, which the check reports.
 */
final class PolicyFile {

    /** Where the file lives. Nothing reads it while the application runs: it is checked, not enforced. */
    static final String PATH = "src/main/resources/data/permissions.json";

    static final List<String> KEYS = List.of("role_names", "public", "public_restricted", "legacy_open", "signed_in", "roles");
    private static final Pattern REQUEST = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH) /\\S*$");
    private static final Pattern ROLE_NAME = Pattern.compile("^ROLE_[A-Z_]+$");

    /** Each request's level, and which list holds it, for a person to read. */
    record Entry(String level, String list) {
    }

    record Policy(Set<String> roleNames, Map<String, Entry> entries) {
        /** The level the file gives a request, or null when it says nothing about it. */
        String levelOf(String request) {
            Entry entry = entries.get(request);
            return entry == null ? null : entry.level();
        }
    }

    private PolicyFile() {
    }

    static Policy parse(String json) {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw refuse("not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw refuse("the file must be one object");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!KEYS.contains(name)) {
                throw refuse("unknown key " + name + "; the keys are " + KEYS);
            }
        }
        for (String key : KEYS) {
            if (!root.has(key)) {
                throw refuse("the file needs the key " + key);
            }
            if (!root.get(key).isArray()) {
                throw refuse(key + " must be a list");
            }
        }

        Set<String> roleNames = new TreeSet<>();
        for (JsonNode node : root.get("role_names")) {
            String name = text(node, "role_names");
            if (!ROLE_NAME.matcher(name).matches()) {
                throw refuse(name + " is not a role name");
            }
            if (!roleNames.add(name)) {
                throw refuse(name + " is declared twice");
            }
        }
        if (roleNames.isEmpty()) {
            throw refuse("role_names is empty");
        }

        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String list : List.of("public", "public_restricted")) {
            for (String request : requests(root.get(list), list)) {
                place(entries, request, "anyone", list);
            }
        }
        int number = 0;
        for (JsonNode group : root.get("legacy_open")) {
            String name = "legacy_open group " + (++number);
            only(group, name, "owner", "requests");
            if (!group.has("owner") || !group.get("owner").isTextual() || group.get("owner").asText().isBlank()) {
                throw refuse(name + " needs an owner");
            }
            List<String> requests = requests(group.get("requests"), name);
            if (requests.isEmpty()) {
                throw refuse(name + " lists no requests");
            }
            for (String request : requests) {
                place(entries, request, "anyone", "legacy_open");
            }
        }
        for (String request : requests(root.get("signed_in"), "signed_in")) {
            place(entries, request, "signed_in", "signed_in");
        }
        Set<String> seenRoleSets = new TreeSet<>();
        number = 0;
        for (JsonNode group : root.get("roles")) {
            String name = "roles group " + (++number);
            only(group, name, "roles", "requests");
            if (!group.has("roles") || !group.get("roles").isArray() || group.get("roles").isEmpty()) {
                throw refuse(name + " needs a list of roles");
            }
            Set<String> roles = new TreeSet<>();
            for (JsonNode node : group.get("roles")) {
                String role = text(node, name);
                if (!roleNames.contains(role)) {
                    throw refuse(name + " names " + role + ", which is not in role_names");
                }
                if (!roles.add(role)) {
                    throw refuse(name + " names " + role + " twice");
                }
            }
            if (!seenRoleSets.add(String.join(",", roles))) {
                throw refuse(name + " repeats the roles of an earlier group: put its requests there");
            }
            List<String> requests = requests(group.get("requests"), name);
            if (requests.isEmpty()) {
                throw refuse(name + " lists no requests");
            }
            for (String request : requests) {
                place(entries, request, "roles:" + String.join(",", roles), "roles");
            }
        }
        return new Policy(roleNames, entries);
    }

    private static IllegalArgumentException refuse(String why) {
        return new IllegalArgumentException("permissions.json: " + why);
    }

    private static String text(JsonNode node, String where) {
        if (!node.isTextual()) {
            throw refuse(where + " holds something that is not a text: " + node);
        }
        return node.asText();
    }

    private static void only(JsonNode group, String name, String... keys) {
        if (!group.isObject()) {
            throw refuse(name + " must be an object");
        }
        for (Iterator<String> fields = group.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!List.of(keys).contains(field)) {
                throw refuse(name + " has an unknown key " + field);
            }
        }
    }

    private static List<String> requests(JsonNode node, String where) {
        if (node == null || !node.isArray()) {
            throw refuse(where + " needs a list of requests");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String request = text(item, where);
            if (!REQUEST.matcher(request).matches()) {
                throw refuse(where + ": \"" + request + "\" is not \"METHOD /path\"");
            }
            out.add(request);
        }
        return out;
    }

    private static void place(Map<String, Entry> entries, String request, String level, String list) {
        Entry before = entries.putIfAbsent(request, new Entry(level, list));
        if (before != null) {
            throw refuse(request + " is in " + before.list() + " and in " + list + ": it can be in only one");
        }
    }
}
