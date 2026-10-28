package com.open.spring.mvc.gist;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Service;
import com.open.spring.mvc.person.PersonJpaRepository;

@Service
public class GistConnectionService {
    public record Status(boolean available, boolean connected, String githubUsername, String updatedAt) {}
    private final GistConnectionRepository connections;
    private final GistTokenCipher cipher;
    private final GitHubGistClient github;
    private final PersonJpaRepository people;
    public GistConnectionService(GistConnectionRepository connections, GistTokenCipher cipher,
            GitHubGistClient github, PersonJpaRepository people) {
        this.connections = connections; this.cipher = cipher; this.github = github; this.people = people;
    }
    public Status status(Authentication authentication) {
        long id = personId(authentication);
        if (!cipher.isAvailable() || !connections.isAvailable()) return new Status(false, false, null, null);
        return connections.find(id).map(row -> new Status(true, true, row.githubUsername(), row.updatedAt()))
            .orElse(new Status(true, false, null, null));
    }
    public Status connect(Authentication authentication, String token) {
        long id = personId(authentication);
        cipher.requireAvailable();
        String username = github.validateIdentity(token);
        // Reject invalid replacements before modifying the previous connection.
        connections.save(id, cipher.encrypt(id, token), username);
        return status(authentication);
    }
    public void disconnect(Authentication authentication) { connections.delete(personId(authentication)); }
    public String create(Authentication authentication, Map<String, Object> body) {
        long id = personId(authentication);
        cipher.requireAvailable();
        var row = connections.find(id).orElseThrow(() -> new GistException(409, "GIST_CONNECTION_REQUIRED",
            "Connect a GitHub Gist token in Profile Settings before exporting."));
        return github.create(cipher.decrypt(id, row.ciphertext()), body);
    }
    private long personId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            throw new GistException(401, "UNAUTHENTICATED", "Sign in to Spring before configuring GitHub or exporting.");
        var person = people.findByUid(authentication.getName());
        if (person == null) throw new GistException(401, "UNAUTHENTICATED", "Spring account could not be found.");
        return person.getId();
    }
}
