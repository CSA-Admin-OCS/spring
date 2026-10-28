package com.open.spring.mvc.gist;

import java.util.*;
import com.open.spring.mvc.person.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GistConnectionServiceTest {
    private final GistConnectionRepository repo = mock(GistConnectionRepository.class);
    private final GitHubGistClient github = mock(GitHubGistClient.class);
    private final PersonJpaRepository people = mock(PersonJpaRepository.class);
    private final GistTokenCipher cipher = new GistTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final GistConnectionService service = new GistConnectionService(repo, cipher, github, people);
    private UsernamePasswordAuthenticationToken user(String uid, long id) {
        Person person = mock(Person.class);
        when(person.getId()).thenReturn(id);
        when(people.findByUid(uid)).thenReturn(person);
        return new UsernamePasswordAuthenticationToken(uid, null, List.of());
    }
    @Test void separateUsersCreateUsingTheirOwnToken() {
        var alice = user("alice", 1); var bob = user("bob", 2);
        var body = Map.<String, Object>of("files", Map.of());
        when(repo.find(1)).thenReturn(Optional.of(new GistConnectionRepository.StoredConnection(cipher.encrypt(1, "alice-token"), "alice", "now")));
        when(repo.find(2)).thenReturn(Optional.of(new GistConnectionRepository.StoredConnection(cipher.encrypt(2, "bob-token"), "bob", "now")));
        when(github.create("alice-token", body)).thenReturn("alice-url");
        when(github.create("bob-token", body)).thenReturn("bob-url");
        assertEquals("alice-url", service.create(alice, body));
        assertEquals("bob-url", service.create(bob, body));
    }
    @Test void missingConnectionNeverUsesASharedToken() {
        var alice = user("alice", 1);
        when(repo.find(1)).thenReturn(Optional.empty());
        assertEquals(409, assertThrows(GistException.class, () -> service.create(alice, Map.of())).getStatus());
        verifyNoInteractions(github);
    }
    @Test void invalidReplacementDoesNotOverwriteConnection() {
        var alice = user("alice", 1);
        when(github.validateIdentity("bad")).thenThrow(new GistException(403, "GIST_TOKEN_INVALID", "Invalid token"));
        assertThrows(GistException.class, () -> service.connect(alice, "bad"));
        verify(repo, never()).save(anyLong(), anyString(), anyString());
    }
    @Test void saveEncryptsAndAllowsDifferentGitHubUsername() {
        var alice = user("alice", 1);
        when(github.validateIdentity("fixture-token")).thenReturn("other-github-name");
        service.connect(alice, "fixture-token");
        verify(repo).save(eq(1L), argThat(value -> value.startsWith("v1:") && cipher.decrypt(1, value).equals("fixture-token")), eq("other-github-name"));
    }
    @Test void disconnectOnlyDeletesCurrentUserAndAnonymousCannotRead() {
        var alice = user("alice", 1);
        service.disconnect(alice);
        verify(repo).delete(1);
        assertEquals(401, assertThrows(GistException.class, () -> service.status(null)).getStatus());
    }
}
