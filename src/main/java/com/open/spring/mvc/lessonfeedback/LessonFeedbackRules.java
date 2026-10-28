package com.open.spring.mvc.lessonfeedback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rules for the feedback a student gives on a lesson taught by a student team.
 *
 * Feedback is saved in two steps. At the lesson the student may rate lesson quality,
 * engagement and popcorn hacks and write pros and grows. At submission the student
 * fills any rating left empty, adds the homework hacks rating and may edit the pros
 * and grows. A rating cannot change once saved, so students cannot revise scores after
 * the fact, and a submission needs complete feedback.
 *
 * The teaching team sees averages and anonymous comments only, and only once enough
 * students have responded that a comment cannot be traced to one classmate.
 *
 * These rules are plain functions so they can be tested without a database; the
 * persistence and endpoints will call them.
 */
@Component
public class LessonFeedbackRules {
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MAX_TEXT_LENGTH = 1000;
    /** Fewer responses than this would let the teaching team guess who wrote a comment. */
    public static final int MIN_RESPONSES_FOR_SUMMARY = 5;

    /** One student's saved feedback on one lesson; a null rating or text has not been given yet. */
    public record Feedback(Integer lesson, Integer engage, Integer popcorn, Integer homework, String pros, String grows) {
        public static final Feedback EMPTY = new Feedback(null, null, null, null, null, null);
    }

    /** What the student sends at the lesson; null fields are left as they are. */
    public record LessonInput(Integer lesson, Integer engage, Integer popcorn, String pros, String grows) {}

    /** What the student sends with the submission; null fields are left as they are. */
    public record SubmissionInput(Integer lesson, Integer engage, Integer popcorn, Integer homework, String pros, String grows) {}

    /** What the teaching team sees; averages and comments stay empty until released. */
    public record Summary(int responseCount, boolean released,
                          Double averageLesson, Double averageEngage, Double averagePopcorn, Double averageHomework,
                          List<String> pros, List<String> grows) {}

    private static final Map<String, Function<Feedback, Integer>> RATINGS = new LinkedHashMap<>();

    static {
        RATINGS.put("lesson", Feedback::lesson);
        RATINGS.put("engage", Feedback::engage);
        RATINGS.put("popcorn", Feedback::popcorn);
        RATINGS.put("homework", Feedback::homework);
    }

    /** The ratings already saved, which the student can no longer change. */
    public Set<String> lockedFields(Feedback saved) {
        Feedback current = saved == null ? Feedback.EMPTY : saved;
        Set<String> locked = new LinkedHashSet<>();
        RATINGS.forEach((name, rating) -> {
            if (rating.apply(current) != null) {
                locked.add(name);
            }
        });
        return locked;
    }

    /** Applies the lesson step; ratings may be partial here because the submission step completes them. */
    public Feedback mergeLessonPhase(Feedback saved, LessonInput input) {
        Objects.requireNonNull(input, "input");
        Feedback current = saved == null ? Feedback.EMPTY : saved;
        return new Feedback(
                mergeRating("lesson", current.lesson(), input.lesson()),
                mergeRating("engage", current.engage(), input.engage()),
                mergeRating("popcorn", current.popcorn(), input.popcorn()),
                current.homework(),
                mergeText("pros", current.pros(), input.pros()),
                mergeText("grows", current.grows(), input.grows()));
    }

    /** Applies the submission step and requires the result to be complete. */
    public Feedback mergeSubmissionPhase(Feedback saved, SubmissionInput input) {
        Objects.requireNonNull(input, "input");
        Feedback current = saved == null ? Feedback.EMPTY : saved;
        Feedback merged = new Feedback(
                mergeRating("lesson", current.lesson(), input.lesson()),
                mergeRating("engage", current.engage(), input.engage()),
                mergeRating("popcorn", current.popcorn(), input.popcorn()),
                mergeRating("homework", current.homework(), input.homework()),
                mergeText("pros", current.pros(), input.pros()),
                mergeText("grows", current.grows(), input.grows()));
        requireComplete(merged);
        return merged;
    }

    /** True when every rating and both comments are present. */
    public boolean isComplete(Feedback feedback) {
        return feedback != null && missingFields(feedback).isEmpty();
    }

    /** Rejects incomplete feedback with a 400 that names the missing fields. */
    public void requireComplete(Feedback feedback) {
        List<String> missing = missingFields(feedback == null ? Feedback.EMPTY : feedback);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Complete your lesson feedback before submitting. Missing: " + String.join(", ", missing));
        }
    }

    /** A member of the teaching team cannot rate their own lesson. */
    public void requireNotTeachingTeam(Long assessorId, Collection<Long> teachingTeamIds) {
        if (assessorId != null && teachingTeamIds != null && teachingTeamIds.contains(assessorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot rate a lesson your team taught.");
        }
    }

    /**
     * Summarizes complete feedback for the teaching team.
     *
     * Pros and grows are shuffled separately so a comment cannot be matched to its
     * writer's ratings, its partner comment or the order students submitted in.
     * Pass a seeded Random in tests.
     */
    public Summary summarize(Collection<Feedback> records, Random random) {
        Objects.requireNonNull(random, "random");
        List<Feedback> complete = new ArrayList<>();
        if (records != null) {
            for (Feedback record : records) {
                if (isComplete(record)) {
                    complete.add(record);
                }
            }
        }
        int count = complete.size();
        if (count < MIN_RESPONSES_FOR_SUMMARY) {
            return new Summary(count, false, null, null, null, null, List.of(), List.of());
        }
        List<String> pros = new ArrayList<>();
        List<String> grows = new ArrayList<>();
        for (Feedback record : complete) {
            pros.add(record.pros());
            grows.add(record.grows());
        }
        Collections.shuffle(pros, random);
        Collections.shuffle(grows, random);
        return new Summary(count, true,
                average(complete, Feedback::lesson),
                average(complete, Feedback::engage),
                average(complete, Feedback::popcorn),
                average(complete, Feedback::homework),
                List.copyOf(pros), List.copyOf(grows));
    }

    private List<String> missingFields(Feedback feedback) {
        List<String> missing = new ArrayList<>();
        RATINGS.forEach((name, rating) -> {
            if (rating.apply(feedback) == null) {
                missing.add(name);
            }
        });
        if (isBlank(feedback.pros())) {
            missing.add("pros");
        }
        if (isBlank(feedback.grows())) {
            missing.add("grows");
        }
        return missing;
    }

    private Integer mergeRating(String field, Integer saved, Integer requested) {
        if (requested == null) {
            return saved;
        }
        if (requested < MIN_RATING || requested > MAX_RATING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be between " + MIN_RATING + " and " + MAX_RATING);
        }
        // Resending the saved value is allowed so a form can post every field it shows.
        if (saved != null && !saved.equals(requested)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, field + " was already saved and cannot be changed");
        }
        return requested;
    }

    private String mergeText(String field, String saved, String requested) {
        if (requested == null) {
            return saved;
        }
        String trimmed = requested.strip();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static Double average(List<Feedback> records, Function<Feedback, Integer> rating) {
        double total = 0;
        for (Feedback record : records) {
            total += rating.apply(record);
        }
        return Math.round(total / records.size() * 100) / 100.0;
    }
}
