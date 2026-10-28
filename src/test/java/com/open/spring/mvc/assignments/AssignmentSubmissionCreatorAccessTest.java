package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.gradingService;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Creator-scoped access to grading, AI summaries and deletion on /api/submissions.
 */
class AssignmentSubmissionCreatorAccessTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private PersonJpaRepository personRepo;

    private AssignmentSubmissionAPIController controller;

    private Person creator;
    private AssignmentSubmission submissionOfA;
    private AssignmentSubmission submissionOfB;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        creator = student(1L, "AdityaS-2010");
        Assignment assignmentA = assignment(10L, "pilot", creator);
        Assignment assignmentB = assignment(11L, "not-mine");

        submissionOfA = submission(100L, assignmentA);
        submissionOfB = submission(200L, assignmentB);

        when(submissionRepo.findById(100L)).thenReturn(Optional.of(submissionOfA));
        when(submissionRepo.findById(200L)).thenReturn(Optional.of(submissionOfB));
        when(submissionRepo.findById(999L)).thenReturn(Optional.empty());
        when(submissionRepo.save(any(AssignmentSubmission.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(controller, "gradingService",
            gradingService(submissionRepo, submissionOfA, submissionOfB));

        register(creator, "ROLE_STUDENT");
    }

    private UserDetails register(Person person, String... authorities) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        return User.withUsername(person.getUid()).password("ignored").authorities(authorities).build();
    }

    @Test
    void creatorCanGradeSummarizeAndDeleteSubmissionsOfTheirAssignment() {
        UserDetails caller = register(creator, "ROLE_STUDENT");

        assertEquals(200, controller.gradeSubmission(100L, caller, 0.9, "nice", null).getStatusCode().value());
        assertEquals(200, controller.saveSubmissionSummary(100L, caller, "summary", 4).getStatusCode().value());
        assertEquals(200, controller.deleteSubmission(100L, caller).getStatusCode().value());

        verify(submissionRepo).applyManualOverride(eq(100L), eq(1), eq(0.9), eq("nice"), any());
        verify(submissionRepo).saveAiSummary(100L, "summary", 4);
        verify(submissionRepo).delete(submissionOfA);
    }

    @Test
    void creatorCannotTouchSubmissionsOfAnAssignmentTheyDoNotOwn() {
        UserDetails caller = register(creator, "ROLE_STUDENT");

        assertEquals(403, controller.gradeSubmission(200L, caller, 0.9, "nice", null).getStatusCode().value());
        assertEquals(403, controller.saveSubmissionSummary(200L, caller, "summary", 4).getStatusCode().value());
        assertEquals(403, controller.deleteSubmission(200L, caller).getStatusCode().value());

        verify(submissionRepo, never()).applyManualOverride(anyLong(), anyInt(), any(), any(), any());
        verify(submissionRepo, never()).saveAiSummary(any(), any(), any());
        verify(submissionRepo, never()).save(any(AssignmentSubmission.class));
        verify(submissionRepo, never()).delete(any(AssignmentSubmission.class));
    }

    @Test
    void aNormalStudentCannotManageSubmissionsOfAnyAssignment() {
        UserDetails caller = register(student(2L, "regular-student"), "ROLE_STUDENT");

        assertEquals(403, controller.gradeSubmission(100L, caller, 0.9, null, null).getStatusCode().value());
        assertEquals(403, controller.saveSubmissionSummary(100L, caller, "summary", null).getStatusCode().value());
        assertEquals(403, controller.deleteSubmission(100L, caller).getStatusCode().value());
    }

    @Test
    void teacherKeepsAccessToEveryAssignment() {
        UserDetails caller = register(teacher(3L, "mort"), "ROLE_TEACHER");

        assertEquals(200, controller.gradeSubmission(100L, caller, 0.9, null, null).getStatusCode().value());
        assertEquals(200, controller.gradeSubmission(200L, caller, 0.8, null, null).getStatusCode().value());
        assertEquals(200, controller.saveSubmissionSummary(200L, caller, "summary", null).getStatusCode().value());
        assertEquals(200, controller.deleteSubmission(200L, caller).getStatusCode().value());
    }

    @Test
    void adminKeepsAccessToEveryAssignment() {
        UserDetails caller = register(admin(4L, "toby"), "ROLE_ADMIN");

        assertEquals(200, controller.gradeSubmission(100L, caller, 0.9, null, null).getStatusCode().value());
        assertEquals(200, controller.gradeSubmission(200L, caller, 0.8, null, null).getStatusCode().value());
        assertEquals(200, controller.saveSubmissionSummary(100L, caller, "summary", null).getStatusCode().value());
        assertEquals(200, controller.deleteSubmission(100L, caller).getStatusCode().value());
    }

    @Test
    void missingSubmissionsReturnNotFound() {
        UserDetails caller = register(teacher(3L, "mort"), "ROLE_TEACHER");

        assertEquals(404, controller.gradeSubmission(999L, caller, 0.9, null, null).getStatusCode().value());
        assertEquals(404, controller.saveSubmissionSummary(999L, caller, "summary", null).getStatusCode().value());
        assertEquals(404, controller.deleteSubmission(999L, caller).getStatusCode().value());
    }

    @Test
    void unauthenticatedCallersAreRejected() {
        ResponseEntity<?> response = controller.gradeSubmission(100L, null, 0.9, null, null);
        assertEquals(401, response.getStatusCode().value());
        assertEquals(401, controller.saveSubmissionSummary(100L, null, "summary", null).getStatusCode().value());
        assertEquals(401, controller.deleteSubmission(100L, null).getStatusCode().value());
    }

    @Test
    void aDeniedCreatorLearnsNothingAboutTheOtherAssignment() {
        UserDetails caller = register(creator, "ROLE_STUDENT");

        GradingResponse body = (GradingResponse) controller.gradeSubmission(200L, caller, 0.9, null, null).getBody();

        assertEquals(GradingOutcome.NOT_PERMITTED, body.outcome());
        assertNull(body.grading());
        assertNull(body.detail());
    }
}
