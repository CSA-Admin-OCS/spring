package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Delegation and status mapping of the grading endpoints on /api/submissions. */
class AssignmentSubmissionManualGradeTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private GroupsJpaRepository groupRepo;

    @Mock
    private SubmissionGradingService gradingService;

    @Mock
    private SubmissionAiGrader aiGrader;

    private AssignmentSubmissionAPIController controller;
    private Person creator;
    private Person mort;
    private Person author;
    private Assignment ownedAssignment;
    private AssignmentSubmission ownedSubmission;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "groupRepo", groupRepo);
        ReflectionTestUtils.setField(controller, "gradingService", gradingService);
        ReflectionTestUtils.setField(controller, "aiGrader", aiGrader);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        creator = student(1L, "creator-uid");
        mort = teacher(3L, "mort");
        author = student(5L, "author-uid");
        ownedAssignment = assignment(10L, "pilot", creator);
        ownedAssignment.setAssignmentType("link");
        ownedSubmission = submission(100L, ownedAssignment);
        ownedSubmission.setSubmitter(author);
        ownedSubmission.setContent(Map.of("type", "link", "url", "https://example.com/v1", "notes", ""));

        when(submissionRepo.findById(100L)).thenReturn(Optional.of(ownedSubmission));
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(ownedAssignment));
        // Teachers and the creator may grade the submission; its author and classmates may not.
        when(gradingService.canManage(eq(100L), any())).thenAnswer(call -> {
            Person person = call.getArgument(1);
            return person == mort || person == creator;
        });
    }

    private UserDetails register(Person person, String... authorities) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        return User.withUsername(person.getUid()).password("ignored").authorities(authorities).build();
    }

    private static SubmissionGradingState state(int revision, Double effective, Double ai, Double manual) {
        return new SubmissionGradingState(100L, revision, effective, null, null, ai, null, null,
                manual != null, manual, null, false, null);
    }

    private static GradingResponse body(ResponseEntity<?> response) {
        return assertInstanceOf(GradingResponse.class, response.getBody());
    }

    // ---- manual grade ----

    @Test
    void aManualGradeIsPassedToTheServiceWithTheReviewedRevision() {
        when(gradingService.applyManualGrade(eq(100L), same(creator), eq(2), eq(0.8), eq("Instructor note")))
                .thenReturn(GradingResult.of(GradingOutcome.MANUAL_GRADE_APPLIED, state(2, 0.8, 0.6, 0.8)));

        ResponseEntity<?> response = controller.gradeSubmission(100L, register(creator, "ROLE_STUDENT"), 0.8,
                "Instructor note", 2);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(GradingOutcome.MANUAL_GRADE_APPLIED, body(response).outcome());
        assertEquals(GradeSource.MANUAL, body(response).grading().source());
        assertEquals(0.6, body(response).grading().aiGrade());
    }

    @Test
    void gradingAnOutdatedRevisionIsAConflict() {
        when(gradingService.applyManualGrade(any(), any(), eq(1), any(), any()))
                .thenReturn(GradingResult.of(GradingOutcome.STALE_REVISION, state(2, null, null, null)));

        ResponseEntity<?> response = controller.gradeSubmission(100L, register(mort, "ROLE_TEACHER"), 0.8, null, 1);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(GradingOutcome.STALE_REVISION, body(response).outcome());
        assertEquals(2, body(response).grading().revision());
    }

    @Test
    void anOutOfRangeGradeIsABadRequest() {
        when(gradingService.applyManualGrade(any(), any(), any(), eq(9.0), any()))
                .thenReturn(GradingResult.of(GradingOutcome.INVALID_GRADE, state(1, null, null, null)));

        ResponseEntity<?> response = controller.gradeSubmission(100L, register(mort, "ROLE_TEACHER"), 9.0, null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(GradingOutcome.INVALID_GRADE, body(response).outcome());
    }

    // ---- clearing an override ----

    @Test
    void clearingAnOverrideReturnsGradingToTheAi() {
        when(gradingService.clearManualOverride(eq(100L), same(mort), eq(1)))
                .thenReturn(GradingResult.of(GradingOutcome.MANUAL_OVERRIDE_CLEARED, state(1, 0.6, 0.6, null)));

        ResponseEntity<?> response = controller.clearManualGrade(100L, register(mort, "ROLE_TEACHER"), 1);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(GradeSource.AI, body(response).grading().source());
    }

    @Test
    void clearingWhenNothingIsOverriddenIsAConflict() {
        when(gradingService.clearManualOverride(any(), any(), any()))
                .thenReturn(GradingResult.of(GradingOutcome.NO_MANUAL_OVERRIDE, state(1, 0.6, 0.6, null)));

        assertEquals(409, controller.clearManualGrade(100L, register(mort, "ROLE_TEACHER"), null).getStatusCode().value());
    }

    @Test
    void aDeniedClearIsForbiddenAndRevealsNoGradingState() {
        when(gradingService.clearManualOverride(any(), any(), any())).thenReturn(GradingResult.notPermitted());

        ResponseEntity<?> response = controller.clearManualGrade(100L, register(author, "ROLE_STUDENT"), null);

        assertEquals(403, response.getStatusCode().value());
        assertEquals(null, body(response).grading());
    }

    @Test
    void unauthenticatedGradingNeverReachesTheService() {
        assertEquals(401, controller.gradeSubmission(100L, null, 0.5, null, null).getStatusCode().value());
        assertEquals(401, controller.clearManualGrade(100L, null, null).getStatusCode().value());
        verify(gradingService, never()).applyManualGrade(any(), any(), any(), any(), any());
        verify(gradingService, never()).clearManualOverride(any(), any(), any());
    }

    // ---- AI grade on request ----

    @Test
    void anAiRegradeOfAnOverriddenSubmissionSucceedsAndKeepsTheManualGrade() {
        when(aiGrader.grade(ownedSubmission))
                .thenReturn(GradingResult.of(GradingOutcome.MANUAL_OVERRIDE_KEPT, state(1, 0.8, 0.5, 0.8)));

        ResponseEntity<?> response = controller.aiGradeSubmission(100L, register(mort, "ROLE_TEACHER"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT, body(response).outcome());
        assertEquals(0.8, body(response).grading().effectiveGrade());
        assertEquals(0.5, body(response).grading().aiGrade());
    }

    @Test
    void theAiGraderGetsTheLoadedAssignmentNotALazyProxy() {
        Assignment loaded = assignment(10L, "pilot", creator);
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(loaded));
        when(aiGrader.grade(any())).thenReturn(GradingResult.of(GradingOutcome.AI_GRADE_APPLIED, state(1, 0.6, 0.6, null)));

        controller.aiGradeSubmission(100L, register(mort, "ROLE_TEACHER"));

        assertEquals(loaded, ownedSubmission.getAssignment());
    }

    @Test
    void aiFailuresMapToTheirStatusesWithTheGradersExplanation() {
        UserDetails caller = register(mort, "ROLE_TEACHER");
        when(aiGrader.grade(any())).thenReturn(
                new GradingResult(GradingOutcome.AI_NOT_GRADEABLE, state(1, null, null, null), "Link can't be read"),
                new GradingResult(GradingOutcome.AI_FAILED, state(1, null, null, null), "Gemini returned HTTP 503"));

        ResponseEntity<?> notGradeable = controller.aiGradeSubmission(100L, caller);
        ResponseEntity<?> failed = controller.aiGradeSubmission(100L, caller);

        assertEquals(422, notGradeable.getStatusCode().value());
        assertEquals("Link can't be read", body(notGradeable).detail());
        assertEquals(502, failed.getStatusCode().value());
        assertEquals(GradingOutcome.AI_FAILED, body(failed).outcome());
    }

    @Test
    void aStudentTeacherMayRequestAnAiRegradeButTheAuthorMayNot() {
        when(aiGrader.grade(any())).thenReturn(GradingResult.of(GradingOutcome.AI_GRADE_APPLIED, state(1, 0.6, 0.6, null)));

        assertEquals(200, controller.aiGradeSubmission(100L, register(creator, "ROLE_STUDENT")).getStatusCode().value());
        ResponseEntity<?> denied = controller.aiGradeSubmission(100L, register(author, "ROLE_STUDENT"));

        assertEquals(403, denied.getStatusCode().value());
        assertEquals(GradingOutcome.NOT_PERMITTED, body(denied).outcome());
        verify(aiGrader, times(1)).grade(any());
    }

    @Test
    void anAiRegradeAfterCloseIsRefusedAsFinal() {
        when(aiGrader.grade(any())).thenReturn(GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, state(1, 0.6, 0.6, null)));

        ResponseEntity<?> response = controller.aiGradeSubmission(100L, register(mort, "ROLE_TEACHER"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(GradingOutcome.ASSIGNMENT_CLOSED, body(response).outcome());
    }

    @Test
    void editingAfterCloseIsRefusedAsFinal() {
        when(gradingService.revise(any(), any(), any()))
                .thenReturn(GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, state(1, 0.6, 0.6, null)));

        ResponseEntity<?> response = controller.updateSubmission(100L, "link", "https://example.com/v2", "", "",
                false, null, register(author, "ROLE_STUDENT"));

        assertEquals(409, response.getStatusCode().value());
        verify(aiGrader, never()).autoGrade(any());
    }

    // ---- revisions ----

    @Test
    void newContentIsSavedAsARevisionAndThenAutoGraded() {
        when(gradingService.revise(same(ownedSubmission), any(), same(author)))
                .thenReturn(GradingResult.of(GradingOutcome.REVISION_CREATED, state(2, null, null, null)));

        ResponseEntity<?> response = controller.updateSubmission(100L, "link", "https://example.com/v2", "", "",
                false, null, register(author, "ROLE_STUDENT"));

        assertEquals(200, response.getStatusCode().value());
        verify(gradingService).revise(same(ownedSubmission),
                eq(Map.of("type", "link", "url", "https://example.com/v2", "notes", "")), same(author));
        verify(aiGrader).autoGrade(ownedSubmission);
    }

    @Test
    void anEditThatKeepsTheContentIsNotReGraded() {
        when(gradingService.revise(any(), any(), any()))
                .thenReturn(GradingResult.of(GradingOutcome.CONTENT_UNCHANGED, state(1, 0.8, 0.6, 0.8)));

        ResponseEntity<?> response = controller.updateSubmission(100L, "link", "https://example.com/v1", "", "new comment",
                false, null, register(author, "ROLE_STUDENT"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("new comment", ownedSubmission.getComment());
        verify(aiGrader, never()).autoGrade(any());
    }

    @Test
    void aRevisionThatLostARaceIsAConflict() {
        when(gradingService.revise(any(), any(), any()))
                .thenReturn(GradingResult.of(GradingOutcome.STALE_REVISION, state(3, null, null, null)));

        ResponseEntity<?> response = controller.updateSubmission(100L, "link", "https://example.com/v2", "", "",
                false, null, register(author, "ROLE_STUDENT"));

        assertEquals(409, response.getStatusCode().value());
        verify(aiGrader, never()).autoGrade(any());
    }

    // ---- history ----

    @Test
    void historyNamesWhoActedAndShowsTheCurrentState() {
        SubmissionGradeEvent override = SubmissionGradeEvent.of(100L, 1, SubmissionGradeEvent.Type.MANUAL_OVERRIDE_SET,
                LocalDateTime.of(2026, 10, 24, 9, 0));
        override.setActorId(3L);
        override.setGrade(0.8);
        SubmissionGradeEvent ai = SubmissionGradeEvent.of(100L, 1, SubmissionGradeEvent.Type.AI_GRADED,
                LocalDateTime.of(2026, 10, 24, 9, 1));
        ai.setGrade(0.5);
        when(gradingService.history(100L)).thenReturn(List.of(override, ai));
        when(personRepo.findAllById(any())).thenReturn(List.of(mort));
        ownedSubmission.setManualOverride(true);
        ownedSubmission.setGrade(0.8);

        ResponseEntity<?> response = controller.getGradingHistory(100L, register(mort, "ROLE_TEACHER"));

        assertEquals(200, response.getStatusCode().value());
        var history = assertInstanceOf(AssignmentSubmissionAPIController.GradingHistoryResponse.class, response.getBody());
        assertEquals(GradeSource.MANUAL, history.current().source());
        assertEquals("mort", history.events().get(0).actorName());
        assertEquals(null, history.events().get(1).actorName());
    }

    @Test
    void theSubmitterCanReadTheirOwnHistory() {
        when(gradingService.history(100L)).thenReturn(List.of());

        assertEquals(200, controller.getGradingHistory(100L, register(author, "ROLE_STUDENT")).getStatusCode().value());
    }

    @Test
    void otherStudentsCannotReadTheHistory() {
        when(groupRepo.findGroupsByPersonId(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getGradingHistory(100L, register(student(9L, "someone-else"), "ROLE_STUDENT"));

        assertEquals(403, response.getStatusCode().value());
        verify(gradingService, never()).history(any());
    }
}
