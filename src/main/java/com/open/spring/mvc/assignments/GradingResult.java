package com.open.spring.mvc.assignments;

/**
 * @param state  grading state after the action; null if the submission is gone or the caller is refused
 * @param detail the AI grader's message for AI failures; null otherwise
 */
public record GradingResult(GradingOutcome outcome, SubmissionGradingState state, String detail) {

    public static GradingResult of(GradingOutcome outcome, SubmissionGradingState state) {
        return new GradingResult(outcome, state, null);
    }

    public static GradingResult notFound() {
        return new GradingResult(GradingOutcome.SUBMISSION_NOT_FOUND, null, null);
    }

    public static GradingResult notPermitted() {
        return new GradingResult(GradingOutcome.NOT_PERMITTED, null, null);
    }
}
