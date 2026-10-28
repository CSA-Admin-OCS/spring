package com.open.spring.mvc.assignments;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.synergy.SynergyGrade;
import com.open.spring.mvc.synergy.SynergyGradeJpaRepository;

/**
 * Copies an assignment's final grades to the gradebook: each student gets the grade of their
 * latest graded submission (group submissions count for every member). Students without a graded
 * submission are left alone.
 *
 * Runs in its own transaction after the close or correction commits, because new gradebook rows
 * take ids from a sequence table on another connection. It is recomputed from the submissions each
 * time, so a run that loses a race with another write is simply retried.
 */
@Component
public class FinalGradeTransfer {

    private static final Logger logger = LoggerFactory.getLogger(FinalGradeTransfer.class);
    static final int ATTEMPTS = 3;

    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final SynergyGradeJpaRepository gradebookRepo;
    private final TransactionTemplate transactions;

    public FinalGradeTransfer(AssignmentSubmissionJPA submissionRepo, AssignmentJpaRepository assignmentRepo,
            SynergyGradeJpaRepository gradebookRepo, PlatformTransactionManager transactionManager) {
        this.submissionRepo = submissionRepo;
        this.assignmentRepo = assignmentRepo;
        this.gradebookRepo = gradebookRepo;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @return how many students' gradebook grades were written */
    public int transfer(Long assignmentId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> copyGrades(assignmentId));
            } catch (ConcurrencyFailureException e) {
                if (attempt == ATTEMPTS) {
                    throw e;
                }
                logger.info("Gradebook transfer for assignment {} hit a concurrent write; retrying", assignmentId);
            }
        }
    }

    private int copyGrades(Long assignmentId) {
        Assignment assignment = assignmentRepo.findById(assignmentId).orElseThrow();
        Map<Long, Person> students = new HashMap<>();
        Map<Long, Double> grades = new LinkedHashMap<>();
        submissionRepo.findByAssignmentId(assignmentId).stream()
                .filter(submission -> submission.getGrade() != null && submission.getSubmitter() != null)
                .sorted(Comparator.comparing(AssignmentSubmission::getId))
                .forEach(submission -> submission.getSubmitter().getMembers().forEach(student -> {
                    students.put(student.getId(), student);
                    grades.put(student.getId(), submission.getGrade());
                }));
        grades.forEach((studentId, grade) -> {
            SynergyGrade entry = gradebookRepo.findByAssignmentIdAndStudentId(assignmentId, studentId)
                    .orElseGet(() -> new SynergyGrade(null, assignment, students.get(studentId)));
            entry.setGrade(grade);
            gradebookRepo.save(entry);
        });
        return grades.size();
    }
}
