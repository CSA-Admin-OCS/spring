package com.open.spring.mvc.aiconnections;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.groups.Groups;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGradingRouterTest {
    private AiConnectionService connections;
    private AiProviderClient client;
    private AiGradingRouter router;
    private Person author;
    @BeforeEach void setup() {
        connections = mock(AiConnectionService.class); client = mock(AiProviderClient.class);
        author = mock(Person.class); when(author.getId()).thenReturn(42L);
        when(client.isConfigured(any())).thenReturn(true);
        when(connections.credential(42, AiProvider.gemini)).thenReturn(Optional.of("personal-gemini"));
        when(connections.credential(42, AiProvider.groq)).thenReturn(Optional.of("personal-groq"));
        router = new AiGradingRouter(connections, client, "system-gemini", "system-groq");
    }
    @Test void personalGeminiSucceedsWithoutCallingOtherProviders() {
        when(client.generate(AiProvider.gemini, "personal-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), eq(0));
        verify(connections, never()).credential(42, AiProvider.groq);
    }
    @Test void operationalFailuresFollowExactFourCredentialOrder() {
        when(client.generate(any(), any(), any(), eq(0))).thenThrow(new AiProviderFailure(true, "Unavailable"));
        doReturn("result").when(client).generate(AiProvider.groq, "system-groq", "prompt", 0);
        assertEquals("result", router.generate("prompt", author));
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 0);
        order.verify(client).generate(AiProvider.groq, "personal-groq", "prompt", 0);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 0);
        order.verify(client).generate(AiProvider.groq, "system-groq", "prompt", 0);
    }
    @Test void missingPersonalGeminiUsesPersonalGroqBeforeSystemGemini() {
        when(connections.credential(42, AiProvider.gemini)).thenReturn(Optional.empty());
        when(client.generate(AiProvider.groq, "personal-groq", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
        verify(client, never()).generate(eq(AiProvider.gemini), any(), any(), eq(0));
    }
    @Test void refusalsAndInvalidOutputDoNotTriggerFallback() {
        when(client.generate(AiProvider.gemini, "personal-gemini", "prompt", 0))
            .thenThrow(new AiProviderFailure(false, "Declined"));
        assertFalse(assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author)).isOperational());
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), eq(0));
    }
    @Test void groupsUseSystemKeysOnly() {
        when(client.generate(AiProvider.gemini, "system-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", mock(Groups.class)));
        verifyNoInteractions(connections);
    }
    @Test void identicalPersonalAndSystemKeysAreAttemptedOnlyOnce() {
        router = new AiGradingRouter(connections, client, "personal-gemini", "personal-groq");
        when(client.generate(any(), any(), any(), eq(0))).thenThrow(new AiProviderFailure(true, "Unavailable"));
        assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author));
        verify(client, times(1)).generate(AiProvider.gemini, "personal-gemini", "prompt", 0);
        verify(client, times(1)).generate(AiProvider.groq, "personal-groq", "prompt", 0);
    }
    @Test void noPersonalStorageStillAllowsExistingSystemGrading() {
        when(connections.credential(anyLong(), any())).thenReturn(Optional.empty());
        when(client.generate(AiProvider.gemini, "system-gemini", "prompt", 0)).thenReturn("result");
        assertEquals("result", router.generate("prompt", author));
    }
    @Test void noKeysFailsWithSanitizedSavedSubmissionMessage() {
        when(connections.credential(anyLong(), any())).thenReturn(Optional.empty());
        router = new AiGradingRouter(connections, client, "", "");
        assertTrue(assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author))
            .getMessage().contains("remains saved"));
        verify(client, never()).generate(any(), any(), any(), eq(0));
    }
    @Test void overflowingResultSkipsBothGroqCredentialsAndRetainsFallbackOrder() {
        when(client.generate(eq(AiProvider.gemini), any(), any(), eq(18)))
            .thenThrow(new AiProviderFailure(true, "Unavailable"));
        assertThrows(AiProviderFailure.class, () -> router.generate("prompt", author, 18));
        verify(client, never()).generate(eq(AiProvider.groq), any(), any(), anyInt());
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 18);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 18);
    }
    @Test void truncatedPersonalGroqContinuesToSystemGeminiWithoutRetry() {
        when(client.generate(any(), any(), any(), eq(7))).thenThrow(new AiProviderFailure(true, "Incomplete"));
        doReturn("result").when(client).generate(AiProvider.gemini, "system-gemini", "prompt", 7);
        assertEquals("result", router.generate("prompt", author, 7));
        var order = inOrder(client);
        order.verify(client).generate(AiProvider.gemini, "personal-gemini", "prompt", 7);
        order.verify(client).generate(AiProvider.groq, "personal-groq", "prompt", 7);
        order.verify(client).generate(AiProvider.gemini, "system-gemini", "prompt", 7);
        verify(client, times(3)).generate(any(), any(), any(), eq(7));
    }
}
