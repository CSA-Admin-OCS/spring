package com.open.spring.mvc.assignments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Map;
import java.util.Optional;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGradingLoggingTest {
    @Test void providerDiagnosticsExposeStatusAndSourceNotKeysPromptsOrExceptionMessages() {
        var client = mock(AiProviderClient.class); var connections = mock(AiConnectionService.class);
        var author = student(1L, "alice");
        when(client.isConfigured(any())).thenReturn(true);
        when(connections.credential(1, AiProvider.gemini)).thenReturn(Optional.of("PRIVATE_KEY"));
        when(connections.credential(1, AiProvider.groq)).thenReturn(Optional.empty());
        when(client.generate(AiProvider.gemini, "PRIVATE_KEY", "PRIVATE_PROMPT", 0))
            .thenThrow(new AiProviderFailure(true, "PRIVATE_PROVIDER_ERROR", 429));
        when(client.generate(AiProvider.gemini, "system-key", "PRIVATE_PROMPT", 0)).thenReturn("PRIVATE_RESPONSE");
        try (var logs = new Capture(AiGradingRouter.class)) {
            var router = new AiGradingRouter(connections, client, "system-key", "");
            assertEquals("PRIVATE_RESPONSE", router.generate("PRIVATE_PROMPT", author, 0));
            String output = logs.output();
            assertTrue(output.contains("source=personal provider=gemini httpStatus=429 fallback=true"));
            assertTrue(output.contains("source=system provider=gemini"));
            assertTrue(output.contains("reason=key_missing"));
            assertFalse(output.contains("PRIVATE_")); assertFalse(output.contains("system-key"));
        }
    }
    @Test void invalidOutputExplainsRejectedFieldsWithoutLoggingStudentContent() throws Exception {
        var router = mock(AiGradingRouter.class);
        when(router.generate(anyString(), any(), eq(0))).thenReturn("{\"score\":0.8,\"feedback\":\"PRIVATE_FEEDBACK\"}");
        var grader = new AssignmentAiGradingService(new ObjectMapper(), mock(FileHandler.class), router, "https://api.github.com", "", "");
        var submission = submission(152L, assignment(1L, "test")); submission.setSubmitter(student(1L, "alice"));
        submission.setContent(Map.of("type", "text", "text", "PRIVATE_SUBMISSION"));
        MDC.put("aiSubmissionId", "previous-context");
        try (var logs = new Capture(AssignmentAiGradingService.class)) {
            assertEquals("failed", grader.grade(submission).status());
            assertTrue(logs.output().contains("submission=152 reason=invalid_grading_result"));
            assertTrue(logs.output().contains("qualityValid=false"));
            assertTrue(logs.output().contains("status=failed"));
            assertFalse(logs.output().contains("PRIVATE_"));
            assertEquals("previous-context", MDC.get("aiSubmissionId"));
        } finally { MDC.remove("aiSubmissionId"); }
    }
    @Test void unexpectedExceptionLogsOnlyTypeAndRestoresThreadContext() {
        var router = mock(AiGradingRouter.class);
        when(router.generate(anyString(), any(), eq(0))).thenThrow(new IllegalStateException("PRIVATE_ERROR_MESSAGE"));
        var grader = new AssignmentAiGradingService(new ObjectMapper(), mock(FileHandler.class), router, "https://api.github.com", "", "");
        var submission = submission(152L, assignment(1L, "test")); submission.setSubmitter(student(1L, "alice"));
        submission.setContent(Map.of("type", "text", "text", "PRIVATE_SUBMISSION"));
        try (var logs = new Capture(AssignmentAiGradingService.class)) {
            assertThrows(IllegalStateException.class, () -> grader.grade(submission));
            assertTrue(logs.output().contains("failureType=IllegalStateException"));
            assertFalse(logs.output().contains("PRIVATE_")); assertNull(MDC.get("aiSubmissionId"));
        }
    }
    private static class Capture implements AutoCloseable {
        private final Logger logger;
        private final Level previous;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Capture(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type); previous = logger.getLevel();
            appender.start(); logger.addAppender(appender); logger.setLevel(Level.INFO);
        }
        String output() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }
        @Override public void close() { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }
}
