package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A submission's grading columns as stored (see {@link AssignmentSubmissionJPA#findGradingState}).
 *
 * @param effectiveGrade the manual grade while overridden, otherwise the AI grade
 * @param aiFeedback     stored in the ai_summary column
 * @param closedAt       when the assignment closed; null while open
 */
public record SubmissionGradingState(
        Long submissionId,
        Integer revision,
        Double effectiveGrade,
        String effectiveFeedback,
        LocalDateTime gradedAt,
        Double aiGrade,
        String aiFeedback,
        Integer qualityScore,
        Boolean manualOverride,
        Double manualGrade,
        String manualFeedback,
        Boolean needsAiRegrade,
        LocalDateTime closedAt) {

    public SubmissionGradingState {
        revision = revision == null ? AssignmentSubmission.FIRST_REVISION : revision;
        manualOverride = Boolean.TRUE.equals(manualOverride);
        needsAiRegrade = Boolean.TRUE.equals(needsAiRegrade);
    }

    public static SubmissionGradingState of(AssignmentSubmission submission) {
        return new SubmissionGradingState(submission.getId(), submission.getRevision(), submission.getGrade(),
                submission.getFeedback(), submission.getGradedAt(), submission.getAiGrade(),
                submission.getAiSummary(), submission.getQualityScore(), submission.getManualOverride(),
                submission.getManualGrade(), submission.getManualFeedback(), submission.getNeedsAiRegrade(),
                submission.getAssignment() == null ? null : submission.getAssignment().getClosedAt());
    }

    /** True once the assignment is closed and the grade is final. */
    @JsonProperty("closed")
    public boolean closed() {
        return closedAt != null;
    }

    @JsonProperty("source")
    public GradeSource source() {
        if (manualOverride) {
            return GradeSource.MANUAL;
        }
        return effectiveGrade == null ? GradeSource.NONE : GradeSource.AI;
    }

    /** Copies this state onto an entity for responses; these columns are never saved from it. */
    void copyTo(AssignmentSubmission submission) {
        submission.setRevision(revision);
        submission.setGrade(effectiveGrade);
        submission.setFeedback(effectiveFeedback);
        submission.setGradedAt(gradedAt);
        submission.setAiGrade(aiGrade);
        submission.setAiSummary(aiFeedback);
        submission.setQualityScore(qualityScore);
        submission.setManualOverride(manualOverride);
        submission.setManualGrade(manualGrade);
        submission.setManualFeedback(manualFeedback);
        submission.setNeedsAiRegrade(needsAiRegrade);
    }
}
