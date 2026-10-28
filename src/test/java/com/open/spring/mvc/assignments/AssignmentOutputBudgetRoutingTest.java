package com.open.spring.mvc.assignments;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.AiGradingRouter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentOutputBudgetRoutingTest {
    private static final String WORK = "student-answer-".repeat(80);
    static Stream<Arguments> contentTypes() throws Exception {
        var mapper = new ObjectMapper();
        String issue = mapper.writeValueAsString(Map.of("title", "Homework", "body", WORK));
        String gist = mapper.writeValueAsString(Map.of("files", Map.of("answer.js", Map.of("content", WORK))));
        String file = mapper.writeValueAsString(Map.of("encoding", "base64", "content", encode(WORK)));
        return Stream.of(
            Arguments.of(Map.of("type", "code", "url", "https://gist.github.com/student/123abcdef"), gist),
            Arguments.of(Map.of("type", "github_issue", "url", "https://github.com/student/repo/issues/1"), issue),
            Arguments.of(Map.of("type", "link", "url", "https://github.com/student/repo/issues/1"), issue),
            Arguments.of(Map.of("type", "link", "url", "https://github.com/student/repo/blob/main/answer.java"), file),
            Arguments.of(Map.of("type", "file", "filename", "answer.txt", "uploadedBy", "student", "storedFilename", "answer"), "{}"),
            Arguments.of(Map.of("type", "file", "filename", "answer.ipynb", "uploadedBy", "student", "storedFilename", "answer"), "{}"),
            Arguments.of(Map.of("type", "text", "text", WORK), "{}"));
    }
    @SuppressWarnings("unchecked")
    @ParameterizedTest @MethodSource("contentTypes")
    void allGradableContentPathsPassRequiredScoreCountWithoutShorteningWork(Map<String, Object> content, String githubJson) throws Exception {
        var router = mock(AiGradingRouter.class);
        var files = mock(FileHandler.class);
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(githubJson);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        String uploaded = content.get("filename") != null && content.get("filename").toString().endsWith(".ipynb")
            ? new ObjectMapper().writeValueAsString(Map.of("cells", java.util.List.of(Map.of("cell_type", "code", "source", WORK)))) : WORK;
        when(files.decodeFile("student", "answer")).thenReturn(encode(uploaded));
        var grader = new AssignmentAiGradingService(new ObjectMapper(), files, router, "https://github-api.test", "", "");
        ReflectionTestUtils.setField(grader, "httpClient", http);
        var assignment = assignment(1L, "test");
        assignment.setHackWeights(AssignmentRubricService.hackWeightsJson(
            "Hack #1 (Weight: 60%) First task. Hack #2 (Weight: 40%) Second task."));
        var submission = submission(1L, assignment);
        var author = student(1L, "student");
        submission.setSubmitter(author);
        submission.setContent(content);
        when(router.generate(anyString(), same(author), eq(2))).thenReturn("""
            {"score":0.9,"quality":3,"feedback":"Some work done.",
             "hacks":[{"hack":"Hack #1","score":0.5},{"hack":"Hack #2","score":0.5}]}
            """);
        assertEquals("graded", grader.grade(submission).status());
        verify(router).generate(contains(WORK), same(author), eq(2));
    }
    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
