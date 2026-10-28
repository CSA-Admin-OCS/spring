package com.open.spring.mvc.aiconnections;

import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.backups.BackupsController;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConnectionServiceTest {
    private SingleConnectionDataSource source;
    private AiConnectionRepository repository;
    private AiConnectionService service;
    private AiProviderClient client;
    private final UsernamePasswordAuthenticationToken alice =
        new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of());
    private final UsernamePasswordAuthenticationToken bob =
        new UsernamePasswordAuthenticationToken("bob", null, java.util.List.of());
    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        repository = new AiConnectionRepository(new JdbcTemplate(source), source); repository.run(null);
        client = mock(AiProviderClient.class); when(client.isConfigured(any())).thenReturn(true);
        var people = mock(PersonJpaRepository.class);
        var first = mock(Person.class); when(first.getId()).thenReturn(1L); when(people.findByUid("alice")).thenReturn(first);
        var second = mock(Person.class); when(second.getId()).thenReturn(2L); when(people.findByUid("bob")).thenReturn(second);
        service = new AiConnectionService(repository, new AiKeyCipher(Base64.getEncoder().encodeToString(new byte[32])), client, people);
    }
    @AfterEach void close() { source.destroy(); }
    @Test void identitiesAndProvidersAreIsolatedAndResponsesContainNoSecret() throws Exception {
        service.connect(alice, AiProvider.gemini, "alice-gemini");
        service.connect(alice, AiProvider.groq, "alice-groq");
        service.connect(bob, AiProvider.gemini, "bob-gemini");
        String response = new ObjectMapper().writeValueAsString(service.status(alice));
        assertFalse(response.contains("alice-gemini")); assertFalse(response.contains("ciphertext"));
        assertTrue(service.status(alice).providers().get("groq").connected());
        assertFalse(service.status(bob).providers().get("groq").connected());
        assertEquals("alice-gemini", service.credential(1, AiProvider.gemini).orElseThrow());
        assertFalse(repository.find(1, AiProvider.gemini).orElseThrow().toString().contains("v1:"));
    }
    @Test void invalidReplacementLeavesOriginalAndDisconnectAffectsOnlyOneProvider() {
        service.connect(alice, AiProvider.gemini, "original");
        service.connect(alice, AiProvider.groq, "other");
        doThrow(new AiConnectionException(422, "INVALID", "Invalid key"))
            .when(client).validate(AiProvider.gemini, "replacement");
        assertThrows(AiConnectionException.class, () -> service.connect(alice, AiProvider.gemini, "replacement"));
        assertEquals("original", service.credential(1, AiProvider.gemini).orElseThrow());
        service.disconnect(alice, AiProvider.gemini);
        assertTrue(service.credential(1, AiProvider.gemini).isEmpty());
        assertEquals("other", service.credential(1, AiProvider.groq).orElseThrow());
    }
    @Test void anonymousAndInvalidInputAreRejectedBeforeProviderCalls() {
        assertEquals(401, assertThrows(AiConnectionException.class, () -> service.status(null)).getStatus());
        assertThrows(AiConnectionException.class, () -> service.connect(alice, AiProvider.gemini, "\nsecret"));
        assertThrows(AiConnectionException.class, () -> service.connect(alice, AiProvider.gemini, "a".repeat(513)));
        verify(client, never()).validate(any(), any());
        assertThrows(AiConnectionException.class, () -> AiProvider.parse("other"));
    }
    @Test void migrationIsIdempotentAndBackupOmitsCredentialTables() {
        service.connect(alice, AiProvider.gemini, "original"); repository.run(null);
        service.connect(alice, AiProvider.gemini, "replacement");
        assertEquals("replacement", service.credential(1, AiProvider.gemini).orElseThrow());
        var jdbc = new JdbcTemplate(source); jdbc.execute("CREATE TABLE ordinary_data (id integer)");
        var backups = new BackupsController(); ReflectionTestUtils.setField(backups, "dataSource", source);
        java.util.Map<?, ?> exported = ReflectionTestUtils.invokeMethod(backups, "exportData");
        assertFalse(exported.containsKey("ai_connections")); assertTrue(exported.containsKey("ordinary_data"));
    }
    @Test void unavailableEncryptionDoesNotExposeOrUseStoredPersonalKeys() {
        service.connect(alice, AiProvider.gemini, "original");
        var people = mock(PersonJpaRepository.class);
        var person = mock(Person.class); when(person.getId()).thenReturn(1L); when(people.findByUid("alice")).thenReturn(person);
        var disabled = new AiConnectionService(repository, new AiKeyCipher(""), client, people);
        assertFalse(disabled.status(alice).available());
        assertTrue(disabled.status(alice).providers().get("gemini").connected());
        assertTrue(disabled.credential(1, AiProvider.gemini).isEmpty());
        assertThrows(AiConnectionException.class, () -> disabled.connect(alice, AiProvider.gemini, "replacement"));
        disabled.disconnect(alice, AiProvider.gemini);
        assertTrue(service.credential(1, AiProvider.gemini).isEmpty());
    }
}
