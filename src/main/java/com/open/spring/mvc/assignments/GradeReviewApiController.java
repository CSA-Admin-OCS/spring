package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Teacher-only endpoints for reviewing a closed assignment's grades (see {@link GradeReviewService}). */
@RestController
@RequestMapping("/api/grade-reviews")
public class GradeReviewApiController {

    public record OpenReviewRequest(GradeReview.Reason reason, String evidence) {
    }

    public record EvidenceRequest(String evidence) {
    }

    public record DecisionRequest(GradeReview.Decision decision, String rationale, Double grade, String feedback) {
    }

    /** A review with teacher names resolved and similarity evidence parsed. */
    public record GradeReviewDto(Long id, Long submissionId, Long assignmentId, GradeReview.Reason reason,
            GradeReview.Status status, Double gradeAtOpen, String evidence, Double aiGrade, String aiFeedback,
            List<Map<String, Object>> similarSubmissions, GradeReview.Decision decision, String rationale,
            Double adjustedGrade, String adjustedFeedback, String openedBy, LocalDateTime openedAt,
            String decidedBy, LocalDateTime decidedAt) {
    }

    public record ReviewResponse(GradingOutcome outcome, GradeReviewDto review, SubmissionGradingState grading,
            String detail) {
    }

    private final GradeReviewService reviewService;
    private final SubmissionAiGrader aiGrader;
    private final PersonJpaRepository personRepo;
    private final ObjectMapper objectMapper;

    public GradeReviewApiController(GradeReviewService reviewService, SubmissionAiGrader aiGrader,
            PersonJpaRepository personRepo, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.aiGrader = aiGrader;
        this.personRepo = personRepo;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<?> reviewsOf(@PathVariable Long submissionId, @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        if (teacher == null) {
            return unauthenticated();
        }
        return reviewService.reviewsOf(submissionId, teacher)
                .<ResponseEntity<?>>map(reviews -> ResponseEntity.ok(toDtos(reviews)))
                .orElseGet(() -> respond(GradeReviewService.ReviewResult.of(GradingOutcome.NOT_PERMITTED)));
    }

    @PostMapping("/submission/{submissionId}")
    public ResponseEntity<?> open(@PathVariable Long submissionId, @RequestBody OpenReviewRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        return teacher == null ? unauthenticated()
                : respond(reviewService.open(submissionId, teacher, request.reason(), request.evidence()));
    }

    @PutMapping("/{reviewId}/evidence")
    public ResponseEntity<?> recordEvidence(@PathVariable Long reviewId, @RequestBody EvidenceRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        return teacher == null ? unauthenticated()
                : respond(reviewService.recordEvidence(reviewId, teacher, request.evidence()));
    }

    /** Adds an AI re-evaluation to the review's evidence. */
    @PostMapping("/{reviewId}/ai-evaluation")
    public ResponseEntity<?> aiEvaluation(@PathVariable Long reviewId, @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        return teacher == null ? unauthenticated() : respond(aiGrader.evaluateForReview(reviewId, teacher));
    }

    /** Adds submissions with identical content to the review's evidence. */
    @PostMapping("/{reviewId}/similarity")
    public ResponseEntity<?> similarity(@PathVariable Long reviewId, @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        return teacher == null ? unauthenticated() : respond(reviewService.collectSimilarity(reviewId, teacher));
    }

    @PostMapping("/{reviewId}/decision")
    public ResponseEntity<?> decide(@PathVariable Long reviewId, @RequestBody DecisionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = person(userDetails);
        return teacher == null ? unauthenticated() : respond(reviewService.decide(reviewId, teacher,
                request.decision(), request.rationale(), request.grade(), request.feedback()));
    }

    private ResponseEntity<ReviewResponse> respond(GradeReviewService.ReviewResult result) {
        GradeReviewDto review = result.review() == null ? null : toDtos(List.of(result.review())).get(0);
        return ResponseEntity.status(GradingResponse.statusOf(result.outcome()))
                .body(new ReviewResponse(result.outcome(), review, result.grading(), result.detail()));
    }

    private List<GradeReviewDto> toDtos(List<GradeReview> reviews) {
        Set<Long> teacherIds = reviews.stream()
                .flatMap(review -> Stream.of(review.getOpenedById(), review.getDecidedById()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = personRepo.findAllById(teacherIds).stream()
                .collect(Collectors.toMap(Person::getId, Person::getName, (first, second) -> first));
        Function<Long, String> name = id -> id == null ? null : names.get(id);
        return reviews.stream().map(review -> new GradeReviewDto(review.getId(), review.getSubmissionId(),
                review.getAssignmentId(), review.getReason(), review.getStatus(), review.getGradeAtOpen(),
                review.getEvidence(), review.getAiGrade(), review.getAiFeedback(),
                similar(review.getSimilarSubmissions()), review.getDecision(), review.getRationale(),
                review.getAdjustedGrade(), review.getAdjustedFeedback(), name.apply(review.getOpenedById()),
                review.getOpenedAt(), name.apply(review.getDecidedById()), review.getDecidedAt())).toList();
    }

    private List<Map<String, Object>> similar(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Person person(UserDetails userDetails) {
        return userDetails == null ? null : personRepo.findByUid(userDetails.getUsername());
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
    }
}
