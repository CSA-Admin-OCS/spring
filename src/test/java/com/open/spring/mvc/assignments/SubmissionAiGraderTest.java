package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubmissionAiGraderTest {

    private final AssignmentAiGradingService aiGradingService = mock(AssignmentAiGradingService.class);
    private final SubmissionGradingService gradingService = mock(SubmissionGradingService.class);
    private final GradeReviewService reviewService = mock(GradeReviewService.class);
    private final SubmissionAiGrader grader = new SubmissionAiGrader(aiGradingService, gradingService, reviewService);

    private AssignmentSubmission submission;

    @BeforeEach
    void setUp() {
        submission = submission(100L, assignment(10L, "pilot"));
        submission.setRevision(3);
        when(gradingService.currentState(100L)).thenReturn(Optional.of(state(null, null, null)));
    }

    private static SubmissionGradingState state(Double effective, Double ai, Double manual) {
        return new SubmissionGradingState(100L, 3, effective, null, null, ai, null, null, manual != null, manual, null, false, null);
    }

    @Test
    void theResultIsRecordedForTheRevisionThatWasGradedEvenIfTheStudentRevisesMeanwhile() throws Exception {
        var graded = AssignmentAiGradingService.GradeResult.graded(0.7, 3, "Good.", false);
        when(aiGradingService.grade(submission)).thenAnswer(call -> {
            submission.setRevision(4); // e.g. a reload elsewhere sees the student's new revision
            return graded;
        });
        when(gradingService.recordAiGrade(100L, 3, graded))
                .thenReturn(GradingResult.of(GradingOutcome.STALE_REVISION, state(null, null, null)));

        assertEquals(GradingOutcome.STALE_REVISION, grader.grade(submission).outcome());
        verify(gradingService).recordAiGrade(100L, 3, graded);
    }

    @Test
    void theSavedStateIsMirroredOntoTheEntity() throws Exception {
        var graded = AssignmentAiGradingService.GradeResult.graded(0.7, 3, "Good.", false);
        when(aiGradingService.grade(submission)).thenReturn(graded);
        when(gradingService.recordAiGrade(100L, 3, graded))
                .thenReturn(GradingResult.of(GradingOutcome.MANUAL_OVERRIDE_KEPT, state(0.9, 0.7, 0.9)));

        grader.grade(submission);

        assertEquals(0.9, submission.getGrade());
        assertEquals(0.7, submission.getAiGrade());
    }

    @Test
    void anExceptionFromTheAiIsAFailureNotAnError() throws Exception {
        when(aiGradingService.grade(submission)).thenThrow(new IllegalStateException("timed out"));
        when(gradingService.currentState(100L)).thenReturn(Optional.of(state(null, null, null)));

        GradingResult result = grader.grade(submission);

        assertEquals(GradingOutcome.AI_FAILED, result.outcome());
        assertEquals("timed out", result.detail());
        verify(gradingService, never()).recordAiGrade(any(), anyInt(), any());
    }

    @Test
    void notGradeableKeepsTheGradersReason() throws Exception {
        when(aiGradingService.grade(submission))
                .thenReturn(AssignmentAiGradingService.GradeResult.notGradeable("No readable content"));

        GradingResult result = grader.grade(submission);

        assertEquals(GradingOutcome.AI_NOT_GRADEABLE, result.outcome());
        assertEquals("No readable content", result.detail());
    }

    @Test
    void aFailedAutoGradeQueuesTheRevisionForARetry() throws Exception {
        when(aiGradingService.grade(submission)).thenReturn(AssignmentAiGradingService.GradeResult.failed("HTTP 503"));
        when(gradingService.flagForAiRegrade(100L, 3)).thenReturn(true);

        assertEquals(GradingOutcome.AI_FAILED, grader.autoGrade(submission).outcome());
        verify(gradingService).flagForAiRegrade(100L, 3);
        verify(gradingService).refresh(submission);
    }

    @Test
    void anAutoGradeThatCannotGradeIsNotRetried() throws Exception {
        when(aiGradingService.grade(submission))
                .thenReturn(AssignmentAiGradingService.GradeResult.notGradeable("No readable content"));

        grader.autoGrade(submission);

        verify(gradingService, never()).flagForAiRegrade(any(), eq(3));
    }

    @Test
    void aClosedAssignmentIsNeverSentToTheAi() throws Exception {
        when(gradingService.currentState(100L)).thenReturn(Optional.of(new SubmissionGradingState(100L, 3, 0.8, null,
                null, 0.8, null, null, false, null, null, false, java.time.LocalDateTime.of(2026, 10, 25, 15, 0))));

        GradingResult result = grader.grade(submission);

        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, result.outcome());
        verify(aiGradingService, never()).grade(any());
        verify(gradingService, never()).recordAiGrade(any(), anyInt(), any());
    }

    // ---- AI re-evaluation as review evidence ----

    @Test
    void aReviewEvaluationIsRecordedOnTheReviewOnly() throws Exception {
        GradeReview review = new GradeReview();
        when(reviewService.aiEvaluationTarget(eq(55L), any()))
                .thenReturn(new GradeReviewService.AiEvaluationTarget(review, submission, null));
        var graded = AssignmentAiGradingService.GradeResult.graded(0.75, 3, "Re-evaluated.", false);
        when(aiGradingService.grade(submission)).thenReturn(graded);
        when(reviewService.recordAiEvaluation(eq(55L), any(), eq(graded)))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.EVIDENCE_RECORDED, review, null));

        assertEquals(GradingOutcome.EVIDENCE_RECORDED, grader.evaluateForReview(55L, null).outcome());
        verify(gradingService, never()).recordAiGrade(any(), anyInt(), any());
    }

    @Test
    void aRefusedReviewEvaluationNeverCallsTheAi() throws Exception {
        when(reviewService.aiEvaluationTarget(eq(55L), any()))
                .thenReturn(GradeReviewService.AiEvaluationTarget.refused(GradingOutcome.NOT_PERMITTED));

        assertEquals(GradingOutcome.NOT_PERMITTED, grader.evaluateForReview(55L, null).outcome());
        verify(aiGradingService, never()).grade(any());
    }
}
