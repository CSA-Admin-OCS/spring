package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.open.spring.mvc.person.Person;

/**
 * Closes an assignment: its grades become final and are copied to the gradebook. Teacher-only,
 * with no reopen; afterwards a grade changes only through a {@link GradeReview}.
 *
 * The close locks the assignment's submissions, then the assignment row (the order every grading
 * path uses), so grading writes in progress finish first and later ones see the closure.
 */
@Service
public class AssignmentClosureService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentClosureService.class);

    /**
     * @param graded      submissions finalized with a grade
     * @param ungraded    submissions finalized without one
     * @param transferred students whose gradebook grade was written
     */
    public record Summary(Long assignmentId, LocalDateTime closedAt, int graded, int ungraded, int transferred) {
        Summary withTransferred(int count) {
            return new Summary(assignmentId, closedAt, graded, ungraded, count);
        }
    }

    public record Result(GradingOutcome outcome, Summary summary) {
    }

    private final AssignmentJpaRepository assignmentRepo;
    private final AssignmentSubmissionJPA submissionRepo;
    private final SubmissionGradingService gradingService;
    private final FinalGradeTransfer gradeTransfer;
    private final AssignmentAuthorizationService authorizationService;
    private final TransactionTemplate transactions;

    public AssignmentClosureService(AssignmentJpaRepository assignmentRepo, AssignmentSubmissionJPA submissionRepo,
            SubmissionGradingService gradingService, FinalGradeTransfer gradeTransfer,
            AssignmentAuthorizationService authorizationService, PlatformTransactionManager transactionManager) {
        this.assignmentRepo = assignmentRepo;
        this.submissionRepo = submissionRepo;
        this.gradingService = gradingService;
        this.gradeTransfer = gradeTransfer;
        this.authorizationService = authorizationService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Result close(Long assignmentId, Person teacher) {
        if (!authorizationService.isTeacherOrAdmin(teacher)) {
            return new Result(GradingOutcome.NOT_PERMITTED, null);
        }
        Result finalized = transactions.execute(status -> finalizeGrades(assignmentId, teacher));
        if (finalized.outcome() != GradingOutcome.ASSIGNMENT_FINALIZED) {
            return finalized;
        }
        int transferred = gradeTransfer.transfer(assignmentId);
        Summary summary = finalized.summary().withTransferred(transferred);
        logger.info("Closed assignment {}: {} graded, {} ungraded, {} gradebook grades written",
                assignmentId, summary.graded(), summary.ungraded(), transferred);
        return new Result(GradingOutcome.ASSIGNMENT_FINALIZED, summary);
    }

    private Result finalizeGrades(Long assignmentId, Person teacher) {
        submissionRepo.lockAssignmentSubmissions(assignmentId);
        LocalDateTime now = LocalDateTime.now();
        if (assignmentRepo.markClosed(assignmentId, now, teacher.getId()) == 0) {
            return new Result(assignmentRepo.existsById(assignmentId)
                    ? GradingOutcome.ASSIGNMENT_CLOSED : GradingOutcome.ASSIGNMENT_NOT_FOUND, null);
        }
        submissionRepo.clearAiQueueForAssignment(assignmentId);

        int graded = 0;
        int ungraded = 0;
        // Loaded after the updates above, so these entities hold the final state.
        for (AssignmentSubmission submission : submissionRepo.findByAssignmentId(assignmentId)) {
            SubmissionGradingState state = SubmissionGradingState.of(submission);
            gradingService.recordFinalized(state, teacher);
            if (state.effectiveGrade() == null) {
                ungraded++;
            } else {
                graded++;
            }
        }
        return new Result(GradingOutcome.ASSIGNMENT_FINALIZED, new Summary(assignmentId, now, graded, ungraded, 0));
    }
}
