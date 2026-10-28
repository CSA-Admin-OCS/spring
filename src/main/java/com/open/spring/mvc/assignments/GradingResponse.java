package com.open.spring.mvc.assignments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response body for the grading endpoints. */
public record GradingResponse(GradingOutcome outcome, SubmissionGradingState grading, String detail) {

    public static ResponseEntity<GradingResponse> from(GradingResult result) {
        return ResponseEntity.status(statusOf(result.outcome()))
                .body(new GradingResponse(result.outcome(), result.state(), result.detail()));
    }

    /** HTTP status for each outcome, shared by the grading, review and closure endpoints. */
    public static HttpStatus statusOf(GradingOutcome outcome) {
        return switch (outcome) {
            case MANUAL_GRADE_APPLIED, MANUAL_OVERRIDE_CLEARED, AI_GRADE_APPLIED, MANUAL_OVERRIDE_KEPT,
                    REVISION_CREATED, CONTENT_UNCHANGED, ASSIGNMENT_FINALIZED, EVIDENCE_RECORDED,
                    REVIEW_NO_CHANGE, GRADE_CORRECTED -> HttpStatus.OK;
            case REVIEW_OPENED -> HttpStatus.CREATED;
            case STALE_REVISION, NO_MANUAL_OVERRIDE, ASSIGNMENT_CLOSED, REVIEW_REQUIRES_CLOSE,
                    REVIEW_ALREADY_OPEN, REVIEW_NOT_OPEN -> HttpStatus.CONFLICT;
            case INVALID_GRADE, REASON_REQUIRED, DECISION_REQUIRED, RATIONALE_REQUIRED -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case AI_NOT_GRADEABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_FAILED -> HttpStatus.BAD_GATEWAY;
            case SUBMISSION_NOT_FOUND, REVIEW_NOT_FOUND, ASSIGNMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
