package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.gradingService;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
 * Covers POST /api/submissions/grade/{submissionId} authorization - specifically that an
 * assigned grader (distinct from a creator, see AssignmentAuthorizationService) can actually
 * grade, and that the grant is correctly scoped.
 *
 * This is the automated counterpart to a live manual run against a real server: a student
 * blocked before being assigned, able to grade after, an unrelated student still blocked, and
 * the grant not leaking to a different assignment or a different submission's grader list.
 */
class AssignmentSubmissionGraderTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private PersonJpaRepository personRepo;

    private AssignmentSubmissionAPIController controller;

    private Assignment assignmentA;
    private Assignment assignmentB;
    private AssignmentSubmission submissionOnA;
    private AssignmentSubmission submissionOnB;
    private Person grader;
    private Person unrelatedStudent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(
            controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        grader = student(1L, "csse-grader");
        unrelatedStudent = student(2L, "unrelated-student");
        assignmentA = assignment(10L, "pilot");
        assignmentB = assignment(11L, "other");
        submissionOnA = submission(100L, assignmentA);
        submissionOnB = submission(101L, assignmentB);

        when(personRepo.findByUid("csse-grader")).thenReturn(grader);
        when(personRepo.findByUid("unrelated-student")).thenReturn(unrelatedStudent);
        when(submissionRepo.findById(100L)).thenReturn(Optional.of(submissionOnA));
        when(submissionRepo.findById(101L)).thenReturn(Optional.of(submissionOnB));
        when(submissionRepo.save(any(AssignmentSubmission.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(controller, "gradingService",
            gradingService(submissionRepo, submissionOnA, submissionOnB));
    }

    private UserDetails caller(String uid) {
        return User.withUsername(uid).password("ignored").authorities(List.of()).build();
    }

    @Test
    void unassignedStudentCannotGradeASubmission() {
        ResponseEntity<?> response = controller.gradeSubmission(100L, caller("csse-grader"), 0.9, "nice", null);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void assignedGraderCanGradeTheSubmission() {
        assignmentA.setAssignedGraders(List.of(grader));

        ResponseEntity<?> response = controller.gradeSubmission(100L, caller("csse-grader"), 0.9, "nice work", null);

        assertEquals(200, response.getStatusCode().value());
        verify(submissionRepo).applyManualOverride(eq(100L), eq(1), eq(0.9), eq("nice work"), any());
    }

    @Test
    void submissionLevelGrantLetsAGraderGradeOnlyThatSubmission() {
        // The narrower /submissions/{id}/assignGraders grant, not the whole-assignment one.
        submissionOnA.setAssignedGraders(List.of(grader));

        ResponseEntity<?> response = controller.gradeSubmission(100L, caller("csse-grader"), 0.85, null, null);

        assertEquals(200, response.getStatusCode().value());
        verify(submissionRepo).applyManualOverride(eq(100L), eq(1), eq(0.85), eq(null), any());
    }

    @Test
    void anUnrelatedStudentStaysBlockedAfterSomeoneElseIsAssigned() {
        assignmentA.setAssignedGraders(List.of(grader));

        ResponseEntity<?> response = controller.gradeSubmission(100L, caller("unrelated-student"), 0.5, null, null);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void theGrantDoesNotLeakToADifferentAssignment() {
        // Assigned as a grader on assignment A only - must not be able to grade B's submission.
        assignmentA.setAssignedGraders(List.of(grader));

        ResponseEntity<?> response = controller.gradeSubmission(101L, caller("csse-grader"), 0.5, null, null);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void theGrantDoesNotLeakToADifferentSubmissionOnTheSameAssignment() {
        // Assigned to grade this one specific submission, not the whole assignment.
        submissionOnA.setAssignedGraders(List.of(grader));
        AssignmentSubmission secondSubmissionOnA = submission(102L, assignmentA);
        ReflectionTestUtils.setField(controller, "gradingService",
            gradingService(submissionRepo, submissionOnA, submissionOnB, secondSubmissionOnA));

        ResponseEntity<?> response = controller.gradeSubmission(102L, caller("csse-grader"), 0.5, null, null);

        assertEquals(403, response.getStatusCode().value());
    }
}
