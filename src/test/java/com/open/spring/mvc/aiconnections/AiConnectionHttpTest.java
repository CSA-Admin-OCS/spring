package com.open.spring.mvc.aiconnections;

import java.util.Base64;
import java.util.List;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC serialization/routing with isolated storage and mocked provider metadata, not a live provider test. */
class AiConnectionHttpTest {
    private SingleConnectionDataSource source;
    private MockMvc mvc;
    private AiConnectionService service;
    private AiProviderClient client;
    private final UsernamePasswordAuthenticationToken alice =
        new UsernamePasswordAuthenticationToken("alice", null, List.of());
    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var repository = new AiConnectionRepository(new JdbcTemplate(source), source); repository.run(null);
        client = mock(AiProviderClient.class); when(client.isConfigured(any())).thenReturn(true);
        var people = mock(PersonJpaRepository.class);
        var person = mock(Person.class); when(person.getId()).thenReturn(1L); when(people.findByUid("alice")).thenReturn(person);
        service = new AiConnectionService(repository, new AiKeyCipher(Base64.getEncoder().encodeToString(new byte[32])), client, people);
        var cors = new CorsConfiguration(); cors.addAllowedOrigin("http://localhost:4500");
        mvc = MockMvcBuilders.standaloneSetup(new AiConnectionApiController(service, new AiRequestGuard(request -> cors)))
            .setControllerAdvice(new AiConnectionExceptionHandler()).build();
    }
    @AfterEach void close() { source.destroy(); }
    @Test void anonymousGetIsJson401WithoutCredentialData() throws Exception {
        mvc.perform(get("/api/ai-connections"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.providers").doesNotExist());
    }
    @Test void putCannotSelectAnotherOwnerAndNeverReturnsKeyOrCiphertext() throws Exception {
        String response = mvc.perform(put("/api/ai-connections/gemini").principal(alice)
                .header("Origin", "http://localhost:4500").header("X-Origin", "client")
                .contentType("application/json").content("{\"key\":\"test-key\",\"personId\":2,\"uid\":\"bob\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.providers.gemini.connected").value(true))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("test-key")); assertFalse(response.contains("ciphertext"));
        assertEquals("test-key", service.credential(1, AiProvider.gemini).orElseThrow());
        assertTrue(service.credential(2, AiProvider.gemini).isEmpty());
    }
    @Test void invalidReplacementAndDisconnectHaveExpectedWireResponses() throws Exception {
        service.connect(alice, AiProvider.groq, "old-key");
        doThrow(new AiConnectionException(422, "AI_KEY_VALIDATION_FAILED", "Invalid key"))
            .when(client).validate(AiProvider.groq, "bad-key");
        mvc.perform(put("/api/ai-connections/groq").principal(alice)
                .header("Origin", "http://localhost:4500").header("X-Origin", "client")
                .contentType("application/json").content("{\"key\":\"bad-key\"}"))
            .andExpect(status().isUnprocessableEntity());
        assertEquals("old-key", service.credential(1, AiProvider.groq).orElseThrow());
        mvc.perform(delete("/api/ai-connections/groq").principal(alice)
                .header("Origin", "http://localhost:4500").header("X-Origin", "client"))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/ai-connections").principal(alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.providers.groq.connected").value(false));
    }
    @Test void forgedOriginAndUnsupportedProviderCannotWrite() throws Exception {
        mvc.perform(put("/api/ai-connections/gemini").principal(alice)
                .header("Origin", "https://foreign.test").header("X-Origin", "client")
                .contentType("application/json").content("{\"key\":\"test-key\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/ai-connections/other").principal(alice)
                .header("Origin", "http://localhost:4500").header("X-Origin", "client")
                .contentType("application/json").content("{\"key\":\"test-key\"}"))
            .andExpect(status().isBadRequest());
        verify(client, never()).validate(any(), any());
    }
}
