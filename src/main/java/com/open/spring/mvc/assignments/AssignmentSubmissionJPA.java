package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;

@Repository
public interface AssignmentSubmissionJPA extends JpaRepository<AssignmentSubmission, Long> {
    List<AssignmentSubmission> findByAssignmentId(Long assignmentId);
    List<AssignmentSubmission> findByAssignmentIdIn(Collection<Long> assignmentIds);
    List<AssignmentSubmission> findByAssignedGraders(Person grader);

    // Note: This used to just be a method signature, but now there's a query too because students is now many to many instead of many to one
    List<AssignmentSubmission> findBySubmitterId(Long id);
    List<AssignmentSubmission> findBySubmitter(Submitter submitter);

    // ========== GRADING WRITES ==========
    // The only writes to the content and grading columns; used by SubmissionGradingService.

    /**
     * Locks the submission row with a no-op UPDATE (SQLite has no SELECT ... FOR UPDATE, and a
     * transaction that reads before writing fails there if another write commits in between).
     * Returns 0 if the submission doesn't exist.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.revision = s.revision WHERE s.id = :id")
    int lockForGrading(@Param("id") Long id);

    /** Reads the grading columns from the database rather than the session. */
    @Query("SELECT new com.open.spring.mvc.assignments.SubmissionGradingState("
            + "s.id, s.revision, s.grade, s.feedback, s.gradedAt, s.aiGrade, s.aiSummary, s.qualityScore, "
            + "s.manualOverride, s.manualGrade, s.manualFeedback, s.needsAiRegrade, a.closedAt) "
            + "FROM AssignmentSubmission s LEFT JOIN s.assignment a WHERE s.id = :id")
    Optional<SubmissionGradingState> findGradingState(@Param("id") Long id);

    /** Stores the AI grade and feedback for the revision. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.aiGrade = :grade, s.aiSummary = :feedback, "
            + "s.qualityScore = :qualityScore "
            + "WHERE s.id = :id AND s.revision = :revision")
    int recordAiGrade(@Param("id") Long id, @Param("revision") int revision, @Param("grade") Double grade,
            @Param("feedback") String feedback, @Param("qualityScore") Integer qualityScore);

    /** Makes the stored AI grade effective unless a manual override is active. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.grade = s.aiGrade, s.feedback = s.aiSummary, "
            + "s.gradedAt = :now, s.needsAiRegrade = :needsAiRegrade "
            + "WHERE s.id = :id AND s.revision = :revision "
            + "AND (s.manualOverride IS NULL OR s.manualOverride = false)")
    int makeAiGradeEffective(@Param("id") Long id, @Param("revision") int revision,
            @Param("needsAiRegrade") boolean needsAiRegrade, @Param("now") LocalDateTime now);

    /** Applies a manual grade and cancels any pending AI re-grade. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.manualOverride = true, s.manualGrade = :grade, "
            + "s.manualFeedback = :feedback, s.grade = :grade, s.feedback = :feedback, s.gradedAt = :now, "
            + "s.needsAiRegrade = false "
            + "WHERE s.id = :id AND s.revision = :revision")
    int applyManualOverride(@Param("id") Long id, @Param("revision") int revision, @Param("grade") Double grade,
            @Param("feedback") String feedback, @Param("now") LocalDateTime now);

    /** Removes an active manual override; the AI grade becomes effective. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.manualOverride = false, s.manualGrade = null, "
            + "s.manualFeedback = null, s.grade = s.aiGrade, s.feedback = s.aiSummary, s.gradedAt = :now "
            + "WHERE s.id = :id AND s.revision = :revision AND s.manualOverride = true")
    int clearManualOverride(@Param("id") Long id, @Param("revision") int revision, @Param("now") LocalDateTime now);

    /** Saves new content as the next revision and resets its grading, including any override. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.content = :content, s.revision = s.revision + 1, "
            + "s.grade = null, s.feedback = null, s.gradedAt = null, s.aiGrade = null, s.aiSummary = null, "
            + "s.qualityScore = null, s.needsAiRegrade = null, s.manualOverride = false, s.manualGrade = null, "
            + "s.manualFeedback = null "
            + "WHERE s.id = :id AND s.revision = :revision")
    int startRevision(@Param("id") Long id, @Param("revision") int revision,
            @Param("content") Map<String, Object> content);

    /** Queues the revision for an AI grade unless it is overridden. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.needsAiRegrade = true "
            + "WHERE s.id = :id AND s.revision = :revision "
            + "AND (s.manualOverride IS NULL OR s.manualOverride = false)")
    int flagForAiRegrade(@Param("id") Long id, @Param("revision") int revision);

    /** Removes the revision from the AI queue. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.needsAiRegrade = false "
            + "WHERE s.id = :id AND s.revision = :revision")
    int clearAiRegradeFlag(@Param("id") Long id, @Param("revision") int revision);

    /** Locks every submission of the assignment (see {@link #lockForGrading}). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.revision = s.revision WHERE s.assignment.id = :assignmentId")
    int lockAssignmentSubmissions(@Param("assignmentId") Long assignmentId);

    /** Removes an assignment's submissions from the AI queue. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.needsAiRegrade = false WHERE s.assignment.id = :assignmentId")
    int clearAiQueueForAssignment(@Param("assignmentId") Long assignmentId);

    /** Saves a summarizer summary; a null quality score keeps the old one. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AssignmentSubmission s SET s.aiSummary = :summary, "
            + "s.qualityScore = COALESCE(:qualityScore, s.qualityScore) WHERE s.id = :id")
    int saveAiSummary(@Param("id") Long id, @Param("summary") String summary,
            @Param("qualityScore") Integer qualityScore);
}
