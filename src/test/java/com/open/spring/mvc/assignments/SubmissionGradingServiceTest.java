package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.open.spring.mvc.person.Person;

/** Outcomes and history entries of {@link SubmissionGradingService}; the SQL is covered by the SQLite test. */
class SubmissionGradingServiceTest {

    private final AssignmentSubmissionJPA submissionRepo = mock(AssignmentSubmissionJPA.class);
    private final AssignmentJpaRepository assignmentRepo = mock(AssignmentJpaRepository.class);
    private final SubmissionGradeEventJPA eventRepo = mock(SubmissionGradeEventJPA.class);
    private final SubmissionGradingService service =
            new SubmissionGradingService(submissionRepo, assignmentRepo, eventRepo, new AssignmentAuthorizationService());

    private static final LocalDateTime CLOSED_AT = LocalDateTime.of(2026, 10, 25, 15, 0);

    private final Person mort = teacher(3L, "mort");
    private AssignmentSubmission submission;

    @BeforeEach
    void setUp() {
        submission = submission(100L, assignment(10L, "pilot"));
        submission.setContent(Map.of("type", "link", "url", "https://example.com/v1"));
        when(submissionRepo.lockForGrading(100L)).thenReturn(1);
        when(submissionRepo.findById(100L)).thenReturn(Optional.of(submission));
        when(assignmentRepo.lockIfOpen(10L)).thenReturn(1);
        // Submitted with the grading history in place; a submission without one is covered in AssignmentSubmissionHistoryTest.
        when(eventRepo.findBySubmissionIdOrderByIdAsc(100L))
                .thenReturn(List.of(SubmissionGradeEvent.of(100L, 1, SubmissionGradeEvent.Type.SUBMITTED, LocalDateTime.of(2026, 10, 1, 9, 0))));
    }

    private static SubmissionGradingState state(int revision, Double effective, Double ai, Double manual) {
        return new SubmissionGradingState(100L, revision, effective, null, null, ai, "AI says", 3,
                manual != null, manual, manual == null ? null : "Teacher says", false, null);
    }

    /** The same state after the assignment closed. */
    private static SubmissionGradingState closed(SubmissionGradingState state) {
        return new SubmissionGradingState(state.submissionId(), state.revision(), state.effectiveGrade(),
                state.effectiveFeedback(), state.gradedAt(), state.aiGrade(), state.aiFeedback(), state.qualityScore(),
                state.manualOverride(), state.manualGrade(), state.manualFeedback(), state.needsAiRegrade(), CLOSED_AT);
    }

    private void stateIs(SubmissionGradingState... states) {
        Optional<SubmissionGradingState> first = Optional.of(states[0]);
        @SuppressWarnings("unchecked")
        Optional<SubmissionGradingState>[] rest = java.util.Arrays.stream(states).skip(1).map(Optional::of)
                .toArray(Optional[]::new);
        when(submissionRepo.findGradingState(100L)).thenReturn(first, rest);
    }

    private List<SubmissionGradeEvent> savedEvents(int count) {
        ArgumentCaptor<SubmissionGradeEvent> events = ArgumentCaptor.forClass(SubmissionGradeEvent.class);
        verify(eventRepo, times(count)).save(events.capture());
        return events.getAllValues();
    }

    // ---- submit ----

    @Test
    void submittingStartsAtRevisionOneAndRecordsTheContent() {
        when(submissionRepo.save(submission)).thenReturn(submission);
        submission.setRevision(7);

        service.submit(submission, student(5L, "author"));

        assertEquals(1, submission.getRevision());
        assertFalse(submission.hasManualOverride());
        SubmissionGradeEvent event = savedEvents(1).get(0);
        assertEquals(SubmissionGradeEvent.Type.SUBMITTED, event.getType());
        assertEquals(submission.getContent(), event.getContent());
        assertEquals(5L, event.getActorId());
    }

    // ---- manual grade ----

    @Test
    void aManualGradeBecomesEffectiveAndIsRecorded() {
        stateIs(state(1, 0.6, 0.6, null), state(1, 0.8, 0.6, 0.8));
        when(submissionRepo.applyManualOverride(eq(100L), eq(1), eq(0.8), eq("Teacher says"), any())).thenReturn(1);

        GradingResult result = service.applyManualGrade(100L, mort, 1, 0.8, "Teacher says");

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, result.outcome());
        assertEquals(GradeSource.MANUAL, result.state().source());
        assertEquals(0.8, submission.getGrade());
        SubmissionGradeEvent event = savedEvents(1).get(0);
        assertEquals(SubmissionGradeEvent.Type.MANUAL_OVERRIDE_SET, event.getType());
        assertEquals(0.8, event.getGrade());
        assertTrue(event.getEffective());
        assertEquals(3L, event.getActorId());
    }

    @Test
    void aManualGradeForARevisionTheGraderNoLongerSeesIsRejected() {
        stateIs(state(2, null, null, null));

        GradingResult result = service.applyManualGrade(100L, mort, 1, 0.8, null);

        assertEquals(GradingOutcome.STALE_REVISION, result.outcome());
        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void withoutAnExpectedRevisionTheCurrentOneIsGraded() {
        stateIs(state(4, null, null, null), state(4, 0.5, null, 0.5));
        when(submissionRepo.applyManualOverride(eq(100L), eq(4), eq(0.5), any(), any())).thenReturn(1);

        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, service.applyManualGrade(100L, mort, null, 0.5, null).outcome());
    }

    @Test
    void theLockIsTakenBeforeAnythingIsRead() {
        stateIs(state(1, null, null, null), state(1, 0.5, null, 0.5));
        when(submissionRepo.applyManualOverride(anyLong(), anyInt(), any(), any(), any())).thenReturn(1);

        service.applyManualGrade(100L, mort, null, 0.5, null);

        InOrder order = inOrder(submissionRepo);
        order.verify(submissionRepo).lockForGrading(100L);
        order.verify(submissionRepo).findById(100L);
        order.verify(submissionRepo).findGradingState(100L);
        order.verify(submissionRepo).applyManualOverride(eq(100L), eq(1), eq(0.5), any(), any());
    }

    @Test
    void onlyPeopleWhoMayManageTheSubmissionCanGradeOrClearIt() {
        Person classmate = student(9L, "classmate");

        GradingResult graded = service.applyManualGrade(100L, classmate, null, 0.5, null);
        GradingResult cleared = service.clearManualOverride(100L, classmate, null);

        assertEquals(GradingOutcome.NOT_PERMITTED, graded.outcome());
        assertEquals(GradingOutcome.NOT_PERMITTED, cleared.outcome());
        assertNull(graded.state(), "a denied caller learns nothing about the submission");
        verify(submissionRepo, never()).findGradingState(anyLong());
        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        verify(submissionRepo, never()).clearManualOverride(anyLong(), anyInt(), any());
    }

    @Test
    void gradesMustBeBetweenZeroAndTheAssignmentsPoints() {
        stateIs(state(1, null, null, null));
        submission.getAssignment().setPoints(5.0);

        for (Double invalid : new Double[] {null, -0.5, 5.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(GradingOutcome.INVALID_GRADE,
                    service.applyManualGrade(100L, mort, null, invalid, null).outcome(), "grade " + invalid);
        }
        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        assertTrue(SubmissionGradingService.isValidGrade(0.0, submission.getAssignment()));
        assertTrue(SubmissionGradingService.isValidGrade(5.0, submission.getAssignment()));
        assertTrue(SubmissionGradingService.isValidGrade(1.0, null));
        assertFalse(SubmissionGradingService.isValidGrade(1.5, null));
    }

    @Test
    void gradingAMissingSubmissionIsNotFound() {
        when(submissionRepo.lockForGrading(100L)).thenReturn(0);

        assertEquals(GradingOutcome.SUBMISSION_NOT_FOUND, service.applyManualGrade(100L, mort, null, 0.5, null).outcome());
        assertEquals(GradingOutcome.SUBMISSION_NOT_FOUND, service.clearManualOverride(100L, mort, null).outcome());
    }

    // ---- clearing an override ----

    @Test
    void clearingAnOverrideRecordsItAndLeavesTheAiGradeEffective() {
        stateIs(state(1, 0.8, 0.6, 0.8), state(1, 0.6, 0.6, null));
        when(submissionRepo.clearManualOverride(eq(100L), eq(1), any())).thenReturn(1);

        GradingResult result = service.clearManualOverride(100L, mort, 1);

        assertEquals(GradingOutcome.MANUAL_OVERRIDE_CLEARED, result.outcome());
        assertEquals(GradeSource.AI, result.state().source());
        verify(submissionRepo, never()).flagForAiRegrade(anyLong(), anyInt());
        SubmissionGradeEvent event = savedEvents(1).get(0);
        assertEquals(SubmissionGradeEvent.Type.MANUAL_OVERRIDE_CLEARED, event.getType());
        assertEquals(0.8, event.getGrade());
    }

    @Test
    void clearingAnOverrideTheAiNeverGradedQueuesAnAiGrade() {
        stateIs(state(1, 0.8, null, 0.8), state(1, null, null, null));
        when(submissionRepo.clearManualOverride(eq(100L), eq(1), any())).thenReturn(1);

        service.clearManualOverride(100L, mort, null);

        verify(submissionRepo).flagForAiRegrade(100L, 1);
    }

    @Test
    void clearingWithoutAnOverrideChangesNothing() {
        stateIs(state(1, 0.6, 0.6, null));

        assertEquals(GradingOutcome.NO_MANUAL_OVERRIDE, service.clearManualOverride(100L, mort, null).outcome());
        verify(submissionRepo, never()).clearManualOverride(anyLong(), anyInt(), any());
        verify(eventRepo, never()).save(any());
    }

    // ---- AI grades ----

    @Test
    void anAiGradeIsAppliedWhenNobodyOverrodeIt() {
        when(submissionRepo.recordAiGrade(100L, 1, 0.6, "AI says", 3)).thenReturn(1);
        when(submissionRepo.makeAiGradeEffective(eq(100L), eq(1), eq(false), any())).thenReturn(1);
        stateIs(state(1, 0.6, 0.6, null));

        GradingResult result = service.recordAiGrade(100L, 1,
                AssignmentAiGradingService.GradeResult.graded(0.6, 3, "AI says", false));

        assertEquals(GradingOutcome.AI_GRADE_APPLIED, result.outcome());
        SubmissionGradeEvent event = savedEvents(1).get(0);
        assertEquals(SubmissionGradeEvent.Type.AI_GRADED, event.getType());
        assertTrue(event.getEffective());
        assertNull(event.getActorId());
    }

    @Test
    void anAiGradeUnderAManualOverrideIsKeptForReferenceOnly() {
        when(submissionRepo.recordAiGrade(100L, 1, 0.6, "AI says", 3)).thenReturn(1);
        when(submissionRepo.makeAiGradeEffective(anyLong(), anyInt(), anyBoolean(), any())).thenReturn(0);
        stateIs(state(1, 0.8, 0.6, 0.8));

        GradingResult result = service.recordAiGrade(100L, 1,
                AssignmentAiGradingService.GradeResult.graded(0.6, 3, "AI says", false));

        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT, result.outcome());
        assertEquals(0.8, result.state().effectiveGrade());
        assertFalse(savedEvents(1).get(0).getEffective());
    }

    @Test
    void anAiGradeForAnOlderRevisionIsDiscarded() {
        when(submissionRepo.recordAiGrade(anyLong(), anyInt(), any(), any(), any())).thenReturn(0);
        stateIs(state(2, null, null, null));

        GradingResult result = service.recordAiGrade(100L, 1,
                AssignmentAiGradingService.GradeResult.graded(0.6, 3, "AI says", false));

        assertEquals(GradingOutcome.STALE_REVISION, result.outcome());
        verify(submissionRepo, never()).makeAiGradeEffective(anyLong(), anyInt(), anyBoolean(), any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void aDefaultRubricGradeStaysQueuedForARegrade() {
        when(submissionRepo.recordAiGrade(anyLong(), anyInt(), any(), any(), any())).thenReturn(1);
        when(submissionRepo.makeAiGradeEffective(anyLong(), anyInt(), anyBoolean(), any())).thenReturn(1);
        stateIs(state(1, 0.6, 0.6, null));

        service.recordAiGrade(100L, 1, AssignmentAiGradingService.GradeResult.graded(0.6, 3, "AI says", true));

        verify(submissionRepo).makeAiGradeEffective(eq(100L), eq(1), eq(true), any());
    }

    @Test
    void onlyGradedResultsCanBeRecorded() {
        assertThrows(IllegalArgumentException.class, () -> service.recordAiGrade(100L, 1,
                AssignmentAiGradingService.GradeResult.failed("Gemini returned HTTP 503")));
    }

    @Test
    void longAiFeedbackIsStoredInFull() {
        String feedback = "x".repeat(2000);
        when(submissionRepo.recordAiGrade(anyLong(), anyInt(), any(), any(), any())).thenReturn(1);
        when(submissionRepo.makeAiGradeEffective(anyLong(), anyInt(), anyBoolean(), any())).thenReturn(1);
        stateIs(state(1, 0.6, 0.6, null));

        service.recordAiGrade(100L, 1, AssignmentAiGradingService.GradeResult.graded(0.6, 3, feedback, false));

        verify(submissionRepo).recordAiGrade(100L, 1, 0.6, feedback, 3);
    }

    // ---- revisions ----

    @Test
    void newContentStartsANewRevisionAndReleasesTheOverride() {
        Map<String, Object> newContent = Map.of("type", "link", "url", "https://example.com/v2");
        stateIs(state(1, 0.8, 0.6, 0.8), state(2, null, null, null));
        when(submissionRepo.startRevision(100L, 1, newContent)).thenReturn(1);
        Person author = student(5L, "author");

        GradingResult result = service.revise(submission, newContent, author);

        assertEquals(GradingOutcome.REVISION_CREATED, result.outcome());
        assertEquals(newContent, submission.getContent());
        assertEquals(2, submission.getRevision());
        assertNull(submission.getGrade());
        List<SubmissionGradeEvent> events = savedEvents(2);
        assertEquals(SubmissionGradeEvent.Type.MANUAL_OVERRIDE_RELEASED_BY_REVISION, events.get(0).getType());
        assertEquals(1, events.get(0).getRevision());
        assertEquals(0.8, events.get(0).getGrade());
        assertEquals(SubmissionGradeEvent.Type.REVISED, events.get(1).getType());
        assertEquals(2, events.get(1).getRevision());
        assertEquals(newContent, events.get(1).getContent());
    }

    @Test
    void aRevisionWithoutAnOverrideOnlyRecordsTheRevision() {
        Map<String, Object> newContent = Map.of("type", "link", "url", "https://example.com/v2");
        stateIs(state(1, 0.6, 0.6, null), state(2, null, null, null));
        when(submissionRepo.startRevision(100L, 1, newContent)).thenReturn(1);

        service.revise(submission, newContent, null);

        assertEquals(SubmissionGradeEvent.Type.REVISED, savedEvents(1).get(0).getType());
    }

    @Test
    void aRevisionTakesTheLockBeforeSavingOrReading() {
        Map<String, Object> newContent = Map.of("type", "link", "url", "https://example.com/v2");
        stateIs(state(1, null, null, null), state(2, null, null, null));
        when(submissionRepo.startRevision(100L, 1, newContent)).thenReturn(1);

        service.revise(submission, newContent, null);

        InOrder order = inOrder(submissionRepo);
        order.verify(submissionRepo).lockForGrading(100L);
        order.verify(submissionRepo).findGradingState(100L);
        order.verify(submissionRepo).save(submission);
        order.verify(submissionRepo).startRevision(100L, 1, newContent);
    }

    @Test
    void anEditWithTheSameContentSavesOtherFieldsButKeepsTheGrading() {
        stateIs(state(1, 0.8, 0.6, 0.8));
        submission.setComment("updated comment");

        GradingResult result = service.revise(submission, Map.of("type", "link", "url", "https://example.com/v1"), null);

        assertEquals(GradingOutcome.CONTENT_UNCHANGED, result.outcome());
        verify(submissionRepo).save(submission);
        verify(submissionRepo, never()).startRevision(anyLong(), anyInt(), any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void aRevisionFromAnOutdatedCopyIsStale() {
        stateIs(state(2, null, null, null));
        when(submissionRepo.startRevision(anyLong(), anyInt(), any())).thenReturn(0);

        GradingResult result = service.revise(submission, Map.of("type", "link", "url", "https://example.com/v3"), null);

        assertEquals(GradingOutcome.STALE_REVISION, result.outcome());
        verify(eventRepo, never()).save(any());
    }

    // ---- AI queue ----

    @Test
    void theAiQueueFlagIsSetOnlyThroughTheGuardedUpdate() {
        stateIs(state(1, null, null, null));
        when(submissionRepo.flagForAiRegrade(100L, 1)).thenReturn(1, 0);

        assertTrue(service.flagForAiRegrade(100L, 1));
        assertFalse(service.flagForAiRegrade(100L, 1));
    }

    // ---- closure: after close the grade is final ----

    @Test
    void aSubmissionToAClosedAssignmentIsRefusedAndNothingIsRecorded() {
        when(assignmentRepo.lockIfOpen(10L)).thenReturn(0);

        GradingResult result = service.submit(submission, student(5L, "hop"));

        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, result.outcome());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void aSubmissionIsSavedBeforeTheAssignmentLockIsTaken() {
        when(submissionRepo.save(submission)).thenReturn(submission);

        assertEquals(GradingOutcome.REVISION_CREATED, service.submit(submission, student(5L, "hop")).outcome());

        InOrder order = inOrder(assignmentRepo, submissionRepo);
        order.verify(submissionRepo).save(submission);
        order.verify(assignmentRepo).lockIfOpen(10L);
    }

    @Test
    void noOrdinaryGradingWriteHappensAfterClose() {
        stateIs(closed(state(1, 0.6, 0.6, null)));
        Person studentTeacher = student(7L, "lex");
        submission.getAssignment().getCreators().add(studentTeacher);

        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, service.applyManualGrade(100L, mort, null, 0.5, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, service.applyManualGrade(100L, studentTeacher, null, 0.5, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, service.clearManualOverride(100L, mort, null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED,
                service.revise(submission, Map.of("type", "link", "url", "https://example.com/v2"), null).outcome());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, service.recordAiGrade(100L, 1,
                AssignmentAiGradingService.GradeResult.graded(0.9, 3, "Late AI", false)).outcome());
        assertFalse(service.flagForAiRegrade(100L, 1));

        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        verify(submissionRepo, never()).clearManualOverride(anyLong(), anyInt(), any());
        verify(submissionRepo, never()).startRevision(anyLong(), anyInt(), any());
        verify(submissionRepo, never()).save(any());
        verify(submissionRepo, never()).recordAiGrade(anyLong(), anyInt(), any(), any(), any());
        verify(submissionRepo, never()).flagForAiRegrade(anyLong(), anyInt());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void anAuthorizedCorrectionIsAManualOverrideLinkedToItsReview() {
        stateIs(closed(state(1, 0.6, 0.6, null)), closed(state(1, 0.8, 0.6, 0.8)));
        when(submissionRepo.applyManualOverride(eq(100L), eq(1), eq(0.8), eq("Corrected"), any())).thenReturn(1);
        GradeReview review = new GradeReview();
        review.setId(55L);

        GradingResult result = service.applyAuthorizedCorrection(submission, review, 0.8, "Corrected", mort);

        assertEquals(GradingOutcome.GRADE_CORRECTED, result.outcome());
        SubmissionGradeEvent event = savedEvents(1).get(0);
        assertEquals(SubmissionGradeEvent.Type.AUTHORIZED_CORRECTION, event.getType());
        assertEquals(55L, event.getReviewId());
        assertEquals(0.8, event.getGrade());
        assertEquals(3L, event.getActorId());
    }

    @Test
    void anAuthorizedCorrectionStillValidatesTheGrade() {
        stateIs(closed(state(1, 0.6, 0.6, null)));

        GradingResult result = service.applyAuthorizedCorrection(submission, new GradeReview(), 5.0, null, mort);

        assertEquals(GradingOutcome.INVALID_GRADE, result.outcome());
        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void closingAndReviewDecisionsAreRecordedInTheHistory() {
        SubmissionGradingState finalState = closed(state(1, 0.6, 0.6, null));
        GradeReview review = new GradeReview();
        review.setId(56L);
        review.setRationale("Rubric applied correctly");

        service.recordFinalized(finalState, mort);
        service.recordReviewNoChange(finalState, review, mort);

        List<SubmissionGradeEvent> events = savedEvents(2);
        assertEquals(SubmissionGradeEvent.Type.FINALIZED, events.get(0).getType());
        assertEquals(0.6, events.get(0).getGrade());
        assertEquals(SubmissionGradeEvent.Type.REVIEW_NO_CHANGE, events.get(1).getType());
        assertEquals("Rubric applied correctly", events.get(1).getFeedback());
        assertEquals(56L, events.get(1).getReviewId());
    }
}
