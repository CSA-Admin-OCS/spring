package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@RestController
@RequestMapping("/api/assignment-submission-view")
public class AssignmentSubmissionViewController {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionViewController.class);

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;

    @Autowired
    private PersonJpaRepository personRepo;

    /**
     * Get all submissions for current user (or all if admin)
     * 
     * @return List of submissions filtered by user role
     */
    @GetMapping("/list")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getSubmissions() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                logger.warn("Unauthorized access to submissions endpoint");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            logger.debug("Processing submissions request for user: {}", auth.getName());

            // Check if user is admin
            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_TEACHER"));

            logger.debug("User is admin: {}", isAdmin);

            List<AssignmentSubmission> submissions;

            if (isAdmin) {
                // Admin sees all submissions
                logger.debug("Fetching all submissions for admin user");
                submissions = submissionRepo.findAll();
            } else {
                // Regular user sees only their own submissions
                String username = auth.getName();
                logger.debug("Fetching submissions for user: {}", username);
                
                Person person = personRepo.findByUid(username);
                if (person == null) {
                    logger.error("User not found with uid: {}", username);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse("User not found"));
                }
                
                logger.debug("Found person with id: {}", person.getId());
                submissions = submissionRepo.findBySubmitterId(person.getId());
            }

            logger.debug("Retrieved {} submissions from database", submissions.size());

            // Convert to DTO with explicit error handling
            List<SubmissionListDTO> dtos = new ArrayList<>();
            for (AssignmentSubmission submission : submissions) {
                try {
                    SubmissionListDTO dto = SubmissionListDTO.from(submission);
                    dtos.add(dto);
                    logger.debug("Successfully converted submission {} to DTO", submission.getId());
                } catch (Exception e) {
                    logger.error("Error converting submission {} to DTO", submission.getId(), e);
                    // Continue processing other submissions instead of failing the whole request
                    SubmissionListDTO fallbackDto = SubmissionListDTO.createFallback(submission);
                    dtos.add(fallbackDto);
                }
            }

            logger.info("Successfully fetched {} submissions", dtos.size());
            return ResponseEntity.ok(dtos);

        } catch (Exception e) {
            logger.error("Unexpected error in getSubmissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error fetching submissions"));
        }
    }

    /**
     * Get current user info including role status
     * Used to determine if user is admin and should see admin tabs
     */
    @GetMapping("/user-info")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getUserInfo() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                logger.debug("Unauthorized access to user-info endpoint");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Not authenticated"));
            }

            boolean isAdmin = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_TEACHER"));

            logger.debug("User info requested - isAdmin: {}", isAdmin);

            return ResponseEntity.ok(new UserInfoDTO(isAdmin, auth.getName()));

        } catch (Exception e) {
            logger.error("Error in getUserInfo", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error fetching user info"));
        }
    }

    /**
     * DTO for submission list display - uses factory method pattern for safe construction
     */
    public static class SubmissionListDTO {
        private Long id;
        private Long assignmentId;
        private String assignmentName;
        private String submitterName;
        private Long submitterId;
        private Map<String, Object> content;
        private String comment;
        private Double grade;
        private String feedback;
        private Boolean isLate;
        private Boolean isGroup;
        private String aiSummary;
        private Integer qualityScore;

        // Private constructor for factory use
        private SubmissionListDTO() {}

        /**
         * Factory method - safe conversion with fallback handling
         */
        public static SubmissionListDTO from(AssignmentSubmission submission) {
            SubmissionListDTO dto = new SubmissionListDTO();
            
            dto.id = submission.getId();
            
            // Handle null assignment
            if (submission.getAssignment() != null) {
                try {
                    dto.assignmentId = submission.getAssignment().getId();
                    dto.assignmentName = submission.getAssignment().getName();
                } catch (Exception e) {
                    dto.assignmentId = null;
                    dto.assignmentName = "Unknown Assignment";
                }
            } else {
                dto.assignmentId = null;
                dto.assignmentName = "Unknown Assignment";
            }
            
            // Handle both Person and Groups submitters
            if (submission.getSubmitter() != null) {
                try {
                    Submitter submitter = submission.getSubmitter();
                    if (submitter instanceof Person) {
                        dto.submitterName = ((Person) submitter).getName();
                        dto.isGroup = false;
                    } else if (submitter instanceof Groups) {
                        dto.submitterName = ((Groups) submitter).getName();
                        dto.isGroup = true;
                    } else {
                        dto.submitterName = "Unknown";
                        dto.isGroup = false;
                    }
                    dto.submitterId = submitter.getId();
                } catch (Exception e) {
                    dto.submitterName = "Unknown";
                    dto.submitterId = null;
                    dto.isGroup = false;
                }
            } else {
                dto.submitterName = "Unknown";
                dto.submitterId = null;
                dto.isGroup = false;
            }
            
            try {
                dto.content = submission.getContent();
                dto.comment = submission.getComment() != null ? submission.getComment() : "";
                dto.grade = submission.getGrade();
                dto.feedback = submission.getFeedback() != null ? submission.getFeedback() : "";
                dto.isLate = submission.getIsLate() != null ? submission.getIsLate() : false;
                dto.aiSummary = submission.getAiSummary();
                dto.qualityScore = submission.getQualityScore();
            } catch (Exception e) {
                dto.content = null;
                dto.comment = "";
                dto.grade = null;
                dto.feedback = "";
                dto.isLate = false;
                dto.aiSummary = null;
                dto.qualityScore = null;
            }

            return dto;
        }

        /**
         * Fallback constructor for when main factory fails - returns minimal valid DTO
         */
        public static SubmissionListDTO createFallback(AssignmentSubmission submission) {
            SubmissionListDTO dto = new SubmissionListDTO();
            try {
                dto.id = submission.getId();
                dto.assignmentId = null;
                dto.assignmentName = "Unknown Assignment";
                dto.submitterName = "Unknown";
                dto.submitterId = null;
                dto.isGroup = false;
                dto.content = null;
                dto.comment = "Error processing submission";
                dto.grade = null;
                dto.feedback = "";
                dto.isLate = false;
                dto.aiSummary = null;
                dto.qualityScore = null;
            } catch (Exception e) {
                logger.error("Error in SubmissionListDTO.createFallback()", e);
            }
            return dto;
        }

        // Getters for Jackson JSON serialization
        public Long getId() { return id; }
        public Long getAssignmentId() { return assignmentId; }
        public String getAssignmentName() { return assignmentName; }
        public String getSubmitterName() { return submitterName; }
        public Long getSubmitterId() { return submitterId; }
        public Map<String, Object> getContent() { return content; }
        public String getComment() { return comment; }
        public Double getGrade() { return grade; }
        public String getFeedback() { return feedback; }
        public Boolean getIsLate() { return isLate; }
        public Boolean getIsGroup() { return isGroup; }
        public String getAiSummary() { return aiSummary; }
        public Integer getQualityScore() { return qualityScore; }
    }

    /**
     * Simple error response with getter for JSON serialization
     */
    public static class ErrorResponse {
        private String error;

        public ErrorResponse(String error) {
            this.error = error;
        }

        public String getError() { return error; }
    }

    /**
     * User info response DTO
     */
    public static class UserInfoDTO {
        private boolean isAdmin;
        private String username;

        public UserInfoDTO(boolean isAdmin, String username) {
            this.isAdmin = isAdmin;
            this.username = username;
        }

        public boolean getIsAdmin() { return isAdmin; }
        public String getUsername() { return username; }
    }
}
