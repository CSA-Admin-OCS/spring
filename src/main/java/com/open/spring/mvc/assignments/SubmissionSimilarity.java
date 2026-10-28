package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.person.Person;

/**
 * Finds other submissions to the same assignment with identical content (same link, text, or file
 * name and size), as evidence for a similarity review. Exact matches only.
 */
final class SubmissionSimilarity {

    enum Basis { SAME_LINK, SAME_TEXT, SAME_FILE }

    record Match(Long submissionId, String submitterName, Set<Basis> basis) {
    }

    // Short answers match by coincidence.
    static final int MIN_TEXT_LENGTH = 40;

    private SubmissionSimilarity() {
    }

    static List<Match> findMatches(AssignmentSubmission target, List<AssignmentSubmission> others) {
        Fingerprint mine = Fingerprint.of(target.getContent());
        List<Match> matches = new ArrayList<>();
        for (AssignmentSubmission other : others) {
            if (other.getId().equals(target.getId())) {
                continue;
            }
            Set<Basis> basis = mine.sharedWith(Fingerprint.of(other.getContent()));
            if (!basis.isEmpty()) {
                matches.add(new Match(other.getId(), submitterName(other), basis));
            }
        }
        return matches;
    }

    private static String submitterName(AssignmentSubmission submission) {
        if (submission.getSubmitter() instanceof Person person) {
            return person.getName();
        }
        return submission.getSubmitter() instanceof Groups group ? group.getName() : null;
    }

    /** Normalized comparable parts of a submission's content; null parts never match. */
    record Fingerprint(String link, String text, String file) {

        static Fingerprint of(Map<String, Object> content) {
            if (content == null) {
                return new Fingerprint(null, null, null);
            }
            return new Fingerprint(normalizeLink(value(content, "url")), normalizeText(firstText(content)),
                    fileKey(value(content, "filename"), value(content, "size")));
        }

        Set<Basis> sharedWith(Fingerprint other) {
            Set<Basis> basis = EnumSet.noneOf(Basis.class);
            if (link != null && link.equals(other.link)) {
                basis.add(Basis.SAME_LINK);
            }
            if (text != null && text.equals(other.text)) {
                basis.add(Basis.SAME_TEXT);
            }
            if (file != null && file.equals(other.file)) {
                basis.add(Basis.SAME_FILE);
            }
            return basis;
        }

        /** Ignores case, scheme, "www.", query, fragment and trailing slashes. */
        static String normalizeLink(String url) {
            if (url == null || url.isBlank()) {
                return null;
            }
            String link = url.trim().toLowerCase(Locale.ROOT)
                    .replaceFirst("^[a-z]+://", "")
                    .replaceFirst("^www\\.", "")
                    .replaceFirst("[?#].*$", "")
                    .replaceFirst("/+$", "");
            return link.isEmpty() ? null : link;
        }

        /** Ignores case and whitespace; null for short answers. */
        static String normalizeText(String text) {
            if (text == null) {
                return null;
            }
            String normalized = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            return normalized.length() < MIN_TEXT_LENGTH ? null : normalized;
        }

        private static String fileKey(String filename, String size) {
            return filename == null || size == null ? null : filename.trim().toLowerCase(Locale.ROOT) + "|" + size;
        }

        private static String firstText(Map<String, Object> content) {
            for (String key : List.of("text", "answer", "response")) {
                String value = value(content, key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static String value(Map<String, Object> content, String key) {
            Object value = content.get(key);
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        }
    }
}
