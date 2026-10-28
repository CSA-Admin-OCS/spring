package com.open.spring.mvc.assignments;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assignment-submissions")
public class AssignmentSubmissionFileApiController {

    private final AssignmentSubmissionUploadService uploadService;

    public AssignmentSubmissionFileApiController(AssignmentSubmissionUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadAssignmentSubmission(
            @RequestParam("assignmentName") String assignmentName,
            @RequestParam(value = "assignmentId", required = false) Long assignmentId,
            @RequestParam("userId") Long userId,
            @RequestParam("username") String username,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "technicalExcellence", required = false) Integer technicalExcellence,
            @RequestParam(value = "communication", required = false) Integer communication,
            @RequestParam(value = "workHabits", required = false) Integer workHabits,
            @RequestParam(value = "aiOrchestration", required = false) Integer aiOrchestration,
            @RequestParam(value = "selfAssessmentReflection", required = false) String selfAssessmentReflection,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            Map<String, Object> response = uploadService.upload(
                    assignmentName,
                    assignmentId,
                    userId,
                    username,
                    file,
                    notes,
                    technicalExcellence,
                    communication,
                    workHabits,
                    aiOrchestration,
                    selfAssessmentReflection,
                    userDetails);
            return ResponseEntity.ok(response);
        } catch (AssignmentSubmissionUploadService.UploadException e) {
            if (e.getOutcome() != null) {
                return GradingResponse.from(GradingResult.of(e.getOutcome(), null));
            }
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }
}