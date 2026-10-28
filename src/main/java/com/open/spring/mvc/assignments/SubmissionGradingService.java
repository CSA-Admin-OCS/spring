package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.open.spring.mvc.person.Person;

/**
 * Changes a submission's content and grading state and records each change in its history.
 *
 * Rules: the AI grade is effective unless a person overrides it; new content starts a new revision
 * and releases any override; writes name the revision they apply to, so stale ones are refused;
 * once the assignment is closed only {@link #applyAuthorizedCorrection} (a teacher's review) can
 * change a grade.
 *
 * Every write takes the submission's row lock ({@link AssignmentSubmissionJPA#lockForGrading})
 * before reading, and loads the submission itself, so callers need no transaction of their own.
 */
@Service
public class SubmissionGradingService {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionGradingService.class);

    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final SubmissionGradeEventJPA eventRepo;
    private final AssignmentAuthorizationService authorizationService;

    public SubmissionGradingService(AssignmentSubmissionJPA submissionRepo, AssignmentJpaRepository assignmentRepo,
            SubmissionGradeEventJPA eventRepo, AssignmentAuthorizationService authorizationService) {
        this.submissionRepo = submissionRepo;
        this.assignmentRepo = assignmentRepo;
        this.eventRepo = eventRepo;
        this.authorizationService = authorizationService;
    }

    /** A submission that passed the checks shared by manual grading actions, or why it didn't. */
    private record Guarded(AssignmentSubmission submission, SubmissionGradingState state, GradingResult refusal) {
        static Guarded refused(GradingResult refusal) {
            return new Guarded(null, null, refusal);
        }
    }

    /**
     * Saves a new submission as revision 1, unless the assignment is closed. The row is saved
     * before the assignment lock is taken because its id comes from a sequence table on another
     * connection, which on SQLite would wait on that lock.
     */
    @Transactional
    public GradingResult submit(AssignmentSubmission submission, Person submittedBy) {
        submission.setRevision(AssignmentSubmission.FIRST_REVISION);
        submission.setManualOverride(false);
        AssignmentSubmission saved = submissionRepo.save(submission);
        if (submission.getAssignment() != null && assignmentRepo.lockIfOpen(submission.getAssignment().getId()) == 0) {
            rollBack();
            return GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, null);
        }
        recordContent(saved.getId(), AssignmentSubmission.FIRST_REVISION, SubmissionGradeEvent.Type.SUBMITTED,
                submittedBy, saved.getContent());
        return GradingResult.of(GradingOutcome.REVISION_CREATED, SubmissionGradingState.of(saved));
    }

    /**
     * Saves an edit. The caller sets the ordinary fields and checks permission; new content
     * becomes the next revision.
     */
    @Transactional
    public GradingResult revise(AssignmentSubmission submission, Map<String, Object> newContent, Person editor) {
        if (submissionRepo.lockForGrading(submission.getId()) == 0) {
            return GradingResult.notFound();
        }
        SubmissionGradingState before = submissionRepo.findGradingState(submission.getId()).orElseThrow();
        if (before.closed()) {
            return GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, before);
        }
        submissionRepo.save(submission);
        if (Objects.equals(submission.getContent(), newContent)) {
            return GradingResult.of(GradingOutcome.CONTENT_UNCHANGED, before);
        }
        int revision = submission.currentRevision();
        if (submissionRepo.startRevision(submission.getId(), revision, newContent) == 0) {
            return GradingResult.of(GradingOutcome.STALE_REVISION, before);
        }
        // A submission from before the grading history has no record of what it held; save that before
        // the new revision's entries, or it is lost. No actor: who submitted it wasn't recorded.
        if (eventRepo.findBySubmissionIdOrderByIdAsc(submission.getId()).isEmpty()) {
            recordContent(submission.getId(), revision, SubmissionGradeEvent.Type.SUBMITTED, null, submission.getContent());
            if (before.effectiveGrade() != null) {
                recordGrade(submission.getId(), revision, before.manualOverride()
                        ? SubmissionGradeEvent.Type.MANUAL_OVERRIDE_SET : SubmissionGradeEvent.Type.AI_GRADED,
                        null, before.effectiveGrade(), before.effectiveFeedback(), true, null);
            }
        }
        if (before.manualOverride()) {
            recordGrade(submission.getId(), revision, SubmissionGradeEvent.Type.MANUAL_OVERRIDE_RELEASED_BY_REVISION,
                    editor, before.manualGrade(), before.manualFeedback(), false, null);
        }
        recordContent(submission.getId(), revision + 1, SubmissionGradeEvent.Type.REVISED, editor, newContent);
        submission.setContent(newContent);
        return reloaded(GradingOutcome.REVISION_CREATED, submission);
    }

    /**
     * Makes a person's grade the effective grade.
     *
     * @param expectedRevision the revision the grader saw, or null for the current one
     */
    @Transactional
    public GradingResult applyManualGrade(Long submissionId, Person grader, Integer expectedRevision,
            Double grade, String feedback) {
        Guarded guarded = guard(submissionId, grader, expectedRevision);
        if (guarded.refusal() != null) {
            return guarded.refusal();
        }
        SubmissionGradingState state = guarded.state();
        if (!isValidGrade(grade, guarded.submission().getAssignment())) {
            return GradingResult.of(GradingOutcome.INVALID_GRADE, state);
        }
        submissionRepo.applyManualOverride(submissionId, state.revision(), grade, feedback, now());
        recordGrade(submissionId, state.revision(), SubmissionGradeEvent.Type.MANUAL_OVERRIDE_SET, grader,
                grade, feedback, true, null);
        return reloaded(GradingOutcome.MANUAL_GRADE_APPLIED, guarded.submission());
    }

    /** Removes a manual override; queues an AI grade if the revision has none yet. */
    @Transactional
    public GradingResult clearManualOverride(Long submissionId, Person actor, Integer expectedRevision) {
        Guarded guarded = guard(submissionId, actor, expectedRevision);
        if (guarded.refusal() != null) {
            return guarded.refusal();
        }
        SubmissionGradingState state = guarded.state();
        if (!state.manualOverride()) {
            return GradingResult.of(GradingOutcome.NO_MANUAL_OVERRIDE, state);
        }
        submissionRepo.clearManualOverride(submissionId, state.revision(), now());
        if (state.aiGrade() == null) {
            submissionRepo.flagForAiRegrade(submissionId, state.revision());
        }
        recordGrade(submissionId, state.revision(), SubmissionGradeEvent.Type.MANUAL_OVERRIDE_CLEARED, actor,
                state.manualGrade(), state.manualFeedback(), false, null);
        return reloaded(GradingOutcome.MANUAL_OVERRIDE_CLEARED, guarded.submission());
    }

    /**
     * Records an AI result for the revision it graded. The AI grade is always stored but only
     * becomes effective without a manual override. Discarded after close.
     */
    @Transactional
    public GradingResult recordAiGrade(Long submissionId, int revision, AssignmentAiGradingService.GradeResult result) {
        if (!result.isGraded()) {
            throw new IllegalArgumentException("Only a graded AI result can be recorded, got " + result.status());
        }
        if (submissionRepo.lockForGrading(submissionId) == 0) {
            return GradingResult.notFound();
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        if (state.closed()) {
            return GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, state);
        }
        if (submissionRepo.recordAiGrade(submissionId, revision, result.grade(), result.feedback(),
                result.qualityScore()) == 0) {
            return GradingResult.of(GradingOutcome.STALE_REVISION, state);
        }
        boolean applied = submissionRepo.makeAiGradeEffective(
                submissionId, revision, result.usedDefaultRubric(), now()) == 1;
        recordGrade(submissionId, revision, SubmissionGradeEvent.Type.AI_GRADED, null,
                result.grade(), result.feedback(), applied, null);
        if (!applied) {
            logger.info("Kept manual grade for submission {}; AI grade saved for reference", submissionId);
        }
        return GradingResult.of(applied ? GradingOutcome.AI_GRADE_APPLIED : GradingOutcome.MANUAL_OVERRIDE_KEPT,
                submissionRepo.findGradingState(submissionId).orElse(null));
    }

    /** Queues the revision for an AI retry unless it is overridden or closed. */
    @Transactional
    public boolean flagForAiRegrade(Long submissionId, int revision) {
        if (submissionRepo.lockForGrading(submissionId) == 0
                || submissionRepo.findGradingState(submissionId).map(SubmissionGradingState::closed).orElse(true)) {
            return false;
        }
        return submissionRepo.flagForAiRegrade(submissionId, revision) == 1;
    }

    @Transactional
    public void clearAiRegradeFlag(Long submissionId, int revision) {
        submissionRepo.clearAiRegradeFlag(submissionId, revision);
    }

    /**
     * Applies a grade approved in a teacher review after close. Runs inside the review's
     * transaction, which holds the submission lock and has checked the teacher.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    GradingResult applyAuthorizedCorrection(AssignmentSubmission submission, GradeReview review,
            Double grade, String feedback, Person teacher) {
        SubmissionGradingState state = submissionRepo.findGradingState(submission.getId()).orElseThrow();
        if (!isValidGrade(grade, submission.getAssignment())) {
            return GradingResult.of(GradingOutcome.INVALID_GRADE, state);
        }
        submissionRepo.applyManualOverride(submission.getId(), state.revision(), grade, feedback, now());
        recordGrade(submission.getId(), state.revision(), SubmissionGradeEvent.Type.AUTHORIZED_CORRECTION, teacher,
                grade, feedback, true, review.getId());
        return reloaded(GradingOutcome.GRADE_CORRECTED, submission);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordReviewNoChange(SubmissionGradingState state, GradeReview review, Person teacher) {
        recordGrade(state.submissionId(), state.revision(), SubmissionGradeEvent.Type.REVIEW_NO_CHANGE, teacher,
                state.effectiveGrade(), review.getRationale(), true, review.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordFinalized(SubmissionGradingState state, Person teacher) {
        recordGrade(state.submissionId(), state.revision(), SubmissionGradeEvent.Type.FINALIZED, teacher,
                state.effectiveGrade(), state.effectiveFeedback(), state.effectiveGrade() != null, null);
    }

    /** Saves a summary from the submission summarizer; never changes a grade. */
    @Transactional
    public Optional<SubmissionGradingState> saveAiSummary(Long submissionId, String summary, Integer qualityScore) {
        submissionRepo.saveAiSummary(submissionId, summary, qualityScore);
        return submissionRepo.findGradingState(submissionId);
    }

    /** Whether the person may grade the submission; for checks made before slow work like an AI call. */
    @Transactional(readOnly = true)
    public boolean canManage(Long submissionId, Person person) {
        return submissionRepo.findById(submissionId)
                .map(submission -> authorizationService.canManageSubmission(person, submission))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<SubmissionGradingState> currentState(Long submissionId) {
        return submissionRepo.findGradingState(submissionId);
    }

    /** Copies the stored grading state onto the entity. */
    @Transactional(readOnly = true)
    public AssignmentSubmission refresh(AssignmentSubmission submission) {
        submissionRepo.findGradingState(submission.getId()).ifPresent(state -> state.copyTo(submission));
        return submission;
    }

    @Transactional(readOnly = true)
    public List<SubmissionGradeEvent> history(Long submissionId) {
        return eventRepo.findBySubmissionIdOrderByIdAsc(submissionId);
    }

    /** A grade from 0 to the assignment's points (1 when it has none). */
    static boolean isValidGrade(Double grade, Assignment assignment) {
        if (grade == null || !Double.isFinite(grade) || grade < 0) {
            return false;
        }
        Double points = assignment == null ? null : assignment.getPoints();
        return grade <= AssignmentRubricService.maxScore(points);
    }

    /** Lock, load, authorize, and check the assignment is open and the revision current. */
    private Guarded guard(Long submissionId, Person person, Integer expectedRevision) {
        if (submissionRepo.lockForGrading(submissionId) == 0) {
            return Guarded.refused(GradingResult.notFound());
        }
        AssignmentSubmission submission = submissionRepo.findById(submissionId).orElseThrow();
        if (!authorizationService.canManageSubmission(person, submission)) {
            return Guarded.refused(GradingResult.notPermitted());
        }
        SubmissionGradingState state = submissionRepo.findGradingState(submissionId).orElseThrow();
        if (state.closed()) {
            return Guarded.refused(GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, state));
        }
        if (expectedRevision != null && !expectedRevision.equals(state.revision())) {
            return Guarded.refused(GradingResult.of(GradingOutcome.STALE_REVISION, state));
        }
        return new Guarded(submission, state, null);
    }

    private static void rollBack() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private GradingResult reloaded(GradingOutcome outcome, AssignmentSubmission submission) {
        SubmissionGradingState state = submissionRepo.findGradingState(submission.getId()).orElse(null);
        if (state != null) {
            state.copyTo(submission);
        }
        return GradingResult.of(outcome, state);
    }

    private void recordGrade(Long submissionId, int revision, SubmissionGradeEvent.Type type, Person actor,
            Double grade, String feedback, boolean effective, Long reviewId) {
        SubmissionGradeEvent event = event(submissionId, revision, type, actor);
        event.setGrade(grade);
        event.setFeedback(feedback);
        event.setEffective(effective);
        event.setReviewId(reviewId);
        eventRepo.save(event);
    }

    private void recordContent(Long submissionId, int revision, SubmissionGradeEvent.Type type, Person actor,
            Map<String, Object> content) {
        SubmissionGradeEvent event = event(submissionId, revision, type, actor);
        event.setContent(content);
        eventRepo.save(event);
    }

    private static SubmissionGradeEvent event(Long submissionId, int revision, SubmissionGradeEvent.Type type,
            Person actor) {
        SubmissionGradeEvent event = SubmissionGradeEvent.of(submissionId, revision, type, now());
        event.setActorId(actor == null ? null : actor.getId());
        return event;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
