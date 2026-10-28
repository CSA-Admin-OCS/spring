package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Authentication, delegation and status mapping of the review and closure endpoints. */
class GradeReviewApiControllerTest {

    private final GradeReviewService reviewService = mock(GradeReviewService.class);
    private final SubmissionAiGrader aiGrader = mock(SubmissionAiGrader.class);
    private final AssignmentClosureService closureService = mock(AssignmentClosureService.class);
    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private final GradeReviewApiController reviews =
            new GradeReviewApiController(reviewService, aiGrader, personRepo, new ObjectMapper());
    private final AssignmentClosureApiController closures = new AssignmentClosureApiController(closureService, personRepo);

    private Person niko;
    private Person hop;

    @BeforeEach
    void setUp() {
        niko = teacher(3L, "niko");
        hop = student(5L, "hop");
        when(personRepo.findByUid("niko")).thenReturn(niko);
        when(personRepo.findByUid("hop")).thenReturn(hop);
        when(personRepo.findAllById(any())).thenReturn(List.of(niko));
    }

    private static UserDetails login(Person person) {
        return User.withUsername(person.getUid()).password("unused").authorities("ROLE_USER").build();
    }

    private static GradeReview review(GradeReview.Status status) {
        GradeReview review = new GradeReview();
        review.setId(55L);
        review.setSubmissionId(100L);
        review.setAssignmentId(10L);
        review.setReason(GradeReview.Reason.SIMILARITY_CHECK);
        review.setStatus(status);
        review.setOpenedById(3L);
        review.setOpenedAt(LocalDateTime.of(2026, 10, 26, 9, 0));
        review.setSimilarSubmissions("[{\"submissionId\":7,\"submitterName\":\"lex\",\"basis\":[\"SAME_LINK\"]}]");
        return review;
    }

    @Test
    void openingAReviewIsCreatedWithTheTeachersNameAndParsedEvidence() {
        when(reviewService.open(eq(100L), same(niko), eq(GradeReview.Reason.SIMILARITY_CHECK), eq("notes")))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.REVIEW_OPENED, review(GradeReview.Status.OPEN), null));

        ResponseEntity<?> response = reviews.open(100L,
                new GradeReviewApiController.OpenReviewRequest(GradeReview.Reason.SIMILARITY_CHECK, "notes"), login(niko));

        assertEquals(201, response.getStatusCode().value());
        var body = assertInstanceOf(GradeReviewApiController.ReviewResponse.class, response.getBody());
        assertEquals("niko", body.review().openedBy());
        assertEquals(7, body.review().similarSubmissions().get(0).get("submissionId"));
    }

    @Test
    void refusalsMapToTheirStatuses() {
        when(reviewService.decide(eq(55L), any(), any(), any(), any(), any()))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.RATIONALE_REQUIRED),
                        GradeReviewService.ReviewResult.of(GradingOutcome.REVIEW_NOT_OPEN),
                        GradeReviewService.ReviewResult.of(GradingOutcome.NOT_PERMITTED));
        var request = new GradeReviewApiController.DecisionRequest(GradeReview.Decision.NO_CHANGE, "", null, null);

        assertEquals(400, reviews.decide(55L, request, login(niko)).getStatusCode().value());
        assertEquals(409, reviews.decide(55L, request, login(niko)).getStatusCode().value());
        ResponseEntity<?> denied = reviews.decide(55L, request, login(hop));
        assertEquals(403, denied.getStatusCode().value());
        assertNull(((GradeReviewApiController.ReviewResponse) denied.getBody()).review(), "a denied caller learns nothing");
    }

    @Test
    void aStudentCannotListReviews() {
        when(reviewService.reviewsOf(100L, hop)).thenReturn(Optional.empty());
        when(reviewService.reviewsOf(100L, niko)).thenReturn(Optional.of(List.of(review(GradeReview.Status.DECIDED))));

        assertEquals(403, reviews.reviewsOf(100L, login(hop)).getStatusCode().value());
        ResponseEntity<?> listed = reviews.reviewsOf(100L, login(niko));
        assertEquals(200, listed.getStatusCode().value());
        assertInstanceOf(List.class, listed.getBody());
    }

    @Test
    void theAiReEvaluationGoesThroughTheAiGrader() {
        when(aiGrader.evaluateForReview(55L, niko))
                .thenReturn(GradeReviewService.ReviewResult.of(GradingOutcome.EVIDENCE_RECORDED, review(GradeReview.Status.OPEN), null));

        assertEquals(200, reviews.aiEvaluation(55L, login(niko)).getStatusCode().value());
    }

    @Test
    void closingReturnsTheSummaryAndRefusesAnonymousCallers() {
        var summary = new AssignmentClosureService.Summary(10L, LocalDateTime.of(2026, 10, 26, 15, 0), 3, 1, 3);
        when(closureService.close(10L, niko))
                .thenReturn(new AssignmentClosureService.Result(GradingOutcome.ASSIGNMENT_FINALIZED, summary));
        when(closureService.close(10L, hop)).thenReturn(new AssignmentClosureService.Result(GradingOutcome.NOT_PERMITTED, null));

        ResponseEntity<?> closed = closures.close(10L, login(niko));
        assertEquals(200, closed.getStatusCode().value());
        assertEquals(summary, ((AssignmentClosureApiController.ClosureResponse) closed.getBody()).summary());
        assertEquals(403, closures.close(10L, login(hop)).getStatusCode().value());
        assertEquals(401, closures.close(10L, null).getStatusCode().value());
        assertEquals(401, reviews.open(100L, new GradeReviewApiController.OpenReviewRequest(null, null), null)
                .getStatusCode().value());
    }

    @Test
    void anonymousCallersNeverReachTheServices() {
        reviews.decide(55L, new GradeReviewApiController.DecisionRequest(null, null, null, null), null);
        reviews.similarity(55L, null);
        reviews.recordEvidence(55L, new GradeReviewApiController.EvidenceRequest("x"), null);

        verifyNoInteractions(reviewService);
    }
}
