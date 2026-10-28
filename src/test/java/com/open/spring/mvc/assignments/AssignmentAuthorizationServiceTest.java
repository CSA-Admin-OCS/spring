package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.syncBot;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;

class AssignmentAuthorizationServiceTest {

    private AssignmentAuthorizationService service;

    private Person creator;
    private Person otherStudent;
    private Assignment assignmentA;
    private Assignment assignmentB;

    @BeforeEach
    void setUp() {
        service = new AssignmentAuthorizationService();
        creator = student(1L, "AdityaS-2010");
        otherStudent = student(2L, "someone-else");
        assignmentA = assignment(10L, "pilot", creator);
        assignmentB = assignment(11L, "other-assignment");
    }

    @Test
    void creatorCanManageTheirOwnAssignment() {
        assertTrue(service.canManage(creator, assignmentA));
    }

    @Test
    void creatorCannotManageAnAssignmentTheyDoNotOwn() {
        assertFalse(service.canManage(creator, assignmentB));
    }

    @Test
    void normalStudentCannotManageAnAssignment() {
        assertFalse(service.canManage(otherStudent, assignmentA));
    }

    @Test
    void teacherCanManageBothAssignments() {
        Person teacher = teacher(3L, "mort");
        assertTrue(service.canManage(teacher, assignmentA));
        assertTrue(service.canManage(teacher, assignmentB));
    }

    @Test
    void adminCanManageBothAssignments() {
        Person admin = admin(4L, "toby");
        assertTrue(service.canManage(admin, assignmentA));
        assertTrue(service.canManage(admin, assignmentB));
    }

    @Test
    void anAssignmentWithoutCreatorsStaysManageableByStaffOnly() {
        assertTrue(service.canManage(admin(4L, "toby"), assignmentB));
        assertFalse(service.canManage(creator, assignmentB));
    }

    @Test
    void creatorMatchesOnDatabaseIdRatherThanEntityIdentity() {
        // Hibernate hands back a different instance for the same account on each load,
        // and Person inherits identity equality, so matching must be id based.
        Person reloadedCreator = student(1L, "AdityaS-2010");
        assertFalse(assignmentA.getCreators().contains(reloadedCreator));
        assertTrue(service.canManage(reloadedCreator, assignmentA));
    }

    @Test
    void creatorMatchesOnUidWhenIdsAreNotYetAssigned() {
        Person unsavedCreator = student(null, "AdityaS-2010");
        Assignment unsaved = assignment(null, "draft", unsavedCreator);
        ReflectionTestUtils.setField(unsavedCreator, "id", null);
        assertTrue(service.canManage(student(null, "AdityaS-2010"), unsaved));
    }

    @Test
    void nullUserOrAssignmentIsNeverManageable() {
        assertFalse(service.canManage(null, assignmentA));
        assertFalse(service.canManage(creator, null));
    }

    @Test
    void assignedGraderCanManageTheWholeAssignment() {
        // The bug this guards: a student set as a grader via /assignGraders/{id} could not
        // actually grade anything, because canManage never looked at assignedGraders.
        Person grader = student(5L, "csse-grader");
        assignmentA.setAssignedGraders(List.of(grader));

        assertTrue(service.canManage(grader, assignmentA));
        assertFalse(service.canManage(grader, assignmentB));
    }

    @Test
    void unassignedStudentStillCannotManageDespiteOtherAssignmentsHavingGraders() {
        Person grader = student(5L, "csse-grader");
        assignmentA.setAssignedGraders(List.of(grader));

        assertFalse(service.canManage(otherStudent, assignmentA));
    }

    @Test
    void assignmentLevelGraderCanManageAnyOfItsSubmissions() {
        Person grader = student(5L, "csse-grader");
        assignmentA.setAssignedGraders(List.of(grader));
        AssignmentSubmission submission = submission(100L, assignmentA);

        assertTrue(service.canManageSubmission(grader, submission));
    }

    @Test
    void submissionLevelGraderCanManageOnlyThatSubmission() {
        // The narrower /submissions/{id}/assignGraders grant: assigned to one submission,
        // not the whole assignment.
        Person grader = student(6L, "peer-grader");
        AssignmentSubmission assigned = submission(100L, assignmentA);
        assigned.setAssignedGraders(List.of(grader));
        AssignmentSubmission notAssigned = submission(101L, assignmentA);

        assertTrue(service.canManageSubmission(grader, assigned));
        assertFalse(service.canManageSubmission(grader, notAssigned));
    }

    @Test
    void unrelatedStudentCannotManageASubmission() {
        AssignmentSubmission submission = submission(100L, assignmentA);
        assertFalse(service.canManageSubmission(otherStudent, submission));
    }

    @Test
    void onlyTheSyncRoleMaySynchronizeCreators() {
        assertTrue(service.canSynchronizeCreators(syncBot(5L, "pages-bot")));
        assertFalse(service.canSynchronizeCreators(creator));
        assertFalse(service.canSynchronizeCreators(teacher(3L, "mort")));
        assertFalse(service.canSynchronizeCreators(admin(4L, "toby")));
        assertFalse(service.canSynchronizeCreators(null));
    }
}
