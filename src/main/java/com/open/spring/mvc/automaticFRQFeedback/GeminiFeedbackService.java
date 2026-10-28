package com.open.spring.mvc.automaticFRQFeedback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GeminiFeedbackService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private com.open.spring.mvc.assignments.AssignmentSubmissionJPA submissionRepo;

    @Autowired
    private com.open.spring.mvc.assignments.SubmissionGradingService gradingService;

    @Autowired
    private com.open.spring.mvc.synergy.SynergyGradeJpaRepository gradesRepo;
    
    public GeminiFeedbackService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate a submission with Gemini, persist structured feedback, and update submission grade.
     * Returns the saved Feedback entity.
     */
    @Transactional
    public Feedback evaluateAndPersistForSubmission(Long submissionId) throws Exception {
        var submissionOpt = submissionRepo.findById(submissionId);
        if (submissionOpt.isEmpty()) {
            throw new IllegalArgumentException("Submission not found: " + submissionId);
        }

        var submission = submissionOpt.get();
        var assignment = submission.getAssignment();

        String year = String.valueOf(java.time.Year.now().getValue());
        Integer questionNumber = 1;
        String topic = assignment != null ? assignment.getType() : "";
        Integer maxScore = assignment != null && assignment.getPoints() != null ? assignment.getPoints().intValue() : 1;
        String rubric = assignment != null ? (assignment.getDescription() == null ? assignment.getName() : assignment.getDescription()) : "";

        // Extract submission text for Gemini — content is now a JSON map
        java.util.Map<String, Object> contentMap = submission.getContent();
        String submissionText = "";
        if (contentMap != null) {
            Object url = contentMap.get("url");
            Object notes = contentMap.get("notes");
            submissionText = (url != null ? String.valueOf(url) : "") + (notes != null && !String.valueOf(notes).isEmpty() ? "\n" + notes : "");
        }

        // Call Gemini
        String prompt = buildPrompt(year, questionNumber, topic, submissionText, rubric, maxScore);
        String raw = callGeminiAPI(prompt);
        FeedbackResponse parsed = parseAndValidate(raw);

        // Serialize structured parts to JSON for storage
        String breakdownJson = toJsonSafe(parsed.getBreakdown());
        String overallJson = toJsonSafe(parsed.getOverallFeedback());
        String strengthsJson = toJsonSafe(parsed.getStrengths());
        String areasJson = toJsonSafe(parsed.getAreasForImprovement());

        Double totalScore = parsed.getTotalScore();
        Integer parsedMax = parsed.getMaxScore() != null ? parsed.getMaxScore() : maxScore;
        double normalized = 0.0;
        if (totalScore != null && parsedMax != null && parsedMax > 0) {
            normalized = totalScore / parsedMax;
        }

        // Save Feedback entity
        Feedback fb = new Feedback(submissionId,
                                   submission.getSubmitter() != null ? submission.getSubmitter().getId() : null,
                                   Integer.valueOf(year),
                                   questionNumber,
                                   1,
                                   totalScore,
                                   parsedMax,
                                   breakdownJson,
                                   overallJson,
                                   strengthsJson,
                                   areasJson,
                                   raw);

        Feedback saved = feedbackRepository.save(fb);

        // Recorded as the AI grade for the revision Gemini read; a manual override stays effective.
        String shortFeedback = parsed.getOverallFeedback() != null && !parsed.getOverallFeedback().isEmpty()
                ? String.join("; ", parsed.getOverallFeedback().subList(0, Math.min(3, parsed.getOverallFeedback().size())))
                : null;

        var outcome = gradingService.recordAiGrade(submissionId, submission.currentRevision(),
                com.open.spring.mvc.assignments.AssignmentAiGradingService.GradeResult.graded(normalized, null, shortFeedback, false))
                .outcome();
        if (outcome != com.open.spring.mvc.assignments.GradingOutcome.AI_GRADE_APPLIED) {
            log.info("Gemini grade for submission {} not made effective: {}", submissionId, outcome);
            return saved;
        }

        // Update SynergyGrade records for students
        try {
            for (com.open.spring.mvc.person.Person student : submission.getSubmitter().getMembers()) {
                com.open.spring.mvc.synergy.SynergyGrade assignedGrade = gradesRepo.findByAssignmentAndStudent(submission.getAssignment(), student);
                if (assignedGrade != null) {
                    assignedGrade.setGrade(normalized);
                    gradesRepo.save(assignedGrade);
                } else {
                    com.open.spring.mvc.synergy.SynergyGrade newGrade = new com.open.spring.mvc.synergy.SynergyGrade(normalized, submission.getAssignment(), student);
                    gradesRepo.save(newGrade);
                }
            }
        } catch (Exception ex) {
            // non-fatal: log and continue
            log.warn("Failed to update per-student SynergyGrade records", ex);
        }

        return saved;
    }

    private String toJsonSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON for storage", e);
            return "";
        }
    }

    private String buildPrompt(String year, Integer questionNumber, String topic,
                               String studentResponse, String rubric, Integer maxScore) {
        return String.format("""
            You are an AP exam grader. Evaluate the following student response and provide structured feedback.

            **FRQ Metadata:**
            - Year: %s
            - Question Number: %d
            - Topic: %s
            - Maximum Score: %d points

            **Student Response:**
            %s

            **Scoring Rubric:**
            %s

            **Instructions:**
            Provide your evaluation in the following JSON format (respond with ONLY valid JSON, no markdown or extra text):
            {
              "totalScore": <number between 0 and %d>,
              "maxScore": %d,
              "breakdown": [
                {
                  "criterion": "<rubric point name>",
                  "pointsEarned": <number>,
                  "pointsPossible": <number>,
                  "feedback": "<specific, actionable feedback>"
                }
              ],
              "overallFeedback": [
                "<key insight 1>",
                "<key insight 2>",
                "<key insight 3>"
              ],
              "strengths": [
                "<strength 1>",
                "<strength 2>"
              ],
              "areasForImprovement": [
                "<specific improvement area 1>",
                "<specific improvement area 2>"
              ]
            }

            Be specific, constructive, and educational in your feedback.
            """,
            year, questionNumber, topic, maxScore, studentResponse, rubric, maxScore, maxScore
        );
    }

    private String callGeminiAPI(String prompt) throws Exception {
        String url = apiUrl + "?key=" + apiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> content = new HashMap<>();

        List<Map<String, String>> parts = new ArrayList<>();
        Map<String, String> part = new HashMap<>();
        part.put("text", prompt);
        parts.add(part);

        content.put("parts", parts);
        contents.add(content);
        requestBody.put("contents", contents);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
            url,
            HttpMethod.POST,
            entity,
            String.class
        );

        if (response.getStatusCode() == HttpStatus.OK) {
            return extractTextFromGeminiResponse(response.getBody());
        } else {
            throw new RuntimeException("Gemini API returned status: " + response.getStatusCode());
        }
    }

    private String extractTextFromGeminiResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.get("candidates");

        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
            JsonNode firstCandidate = candidates.get(0);
            JsonNode content = firstCandidate.get("content");
            if (content == null || content.isNull()) {
                throw new RuntimeException("Gemini response missing content node");
            }
            JsonNode parts = content.get("parts");

            if (parts != null && parts.isArray() && parts.size() > 0) {
                JsonNode text = parts.get(0).get("text");
                if (text != null && !text.isNull()) {
                    return text.asText();
                }
            }
        }

        throw new RuntimeException("Unable to extract text from Gemini response");
    }

    private FeedbackResponse parseAndValidate(String responseText) throws Exception {
        // Remove markdown code blocks if present
        String cleanText = responseText.trim()
            .replaceAll("```json\\n?", "")
            .replaceAll("```\\n?", "");

        FeedbackResponse feedback = objectMapper.readValue(cleanText, FeedbackResponse.class);

        // Validate required fields
        if (feedback.getTotalScore() == null || feedback.getMaxScore() == null ||
            feedback.getBreakdown() == null || feedback.getOverallFeedback() == null) {
            throw new IllegalArgumentException("Missing required fields in feedback response");
        }

        if (feedback.getBreakdown().isEmpty()) {
            throw new IllegalArgumentException("Breakdown cannot be empty");
        }

        return feedback;
    }
}