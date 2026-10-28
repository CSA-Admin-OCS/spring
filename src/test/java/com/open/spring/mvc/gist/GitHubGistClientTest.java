package com.open.spring.mvc.gist;

import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubGistClientTest {
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
    private final GitHubGistClient client = new GitHubGistClient(rest);
    private final String token = "ghp_example_fixture_not_a_real_token";
    private final Map<String, Object> body = Map.of("files", Map.of("code.py", Map.of("content", "print(1)")));
    @AfterEach void verify() { server.verify(); }
    @Test void identityValidationDoesNotCreateGist() {
        server.expect(requestTo("https://api.github.com/user")).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer " + token))
            .andRespond(withSuccess("{\"login\":\"alice\"}", MediaType.APPLICATION_JSON).header("X-OAuth-Scopes", "gist"));
        assertEquals("alice", client.validateIdentity(token));
    }
    @Test void rejectsClassicTokenWithoutGistScope() {
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"alice\"}", MediaType.APPLICATION_JSON).header("X-OAuth-Scopes", "read:user"));
        assertEquals("GIST_PERMISSION_REQUIRED", assertThrows(GistException.class, () -> client.validateIdentity(token)).getCode());
    }
    @Test void createsSecretGistUsingSuppliedToken() {
        server.expect(requestTo("https://api.github.com/gists")).andExpect(header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.public").value(false)).andExpect(jsonPath("$.files['code.py'].content").value("print(1)"))
            .andRespond(withSuccess("{\"html_url\":\"https://gist.github.com/alice/abc\"}", MediaType.APPLICATION_JSON));
        assertEquals("https://gist.github.com/alice/abc", client.create(token, body));
    }
    @Test void unlistedGistReadsDoNotRequireAuthorToken() {
        server.expect(requestTo("https://api.github.com/gists/abcdef123")).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("{\"files\":{},\"description\":\"legacy\"}", MediaType.APPLICATION_JSON));
        assertEquals("legacy", client.read("abcdef123", null).get("description"));
    }
    @Test void obsoleteReadCredentialFallsBackToAnonymous() {
        server.expect(requestTo("https://api.github.com/gists/abcdef123")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://api.github.com/gists/abcdef123")).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("{\"files\":{},\"description\":\"legacy\"}", MediaType.APPLICATION_JSON));
        assertEquals("legacy", client.read("abcdef123", token).get("description"));
    }
    @Test void revokedTokenErrorDoesNotExposeUpstreamBody() {
        server.expect(requestTo("https://api.github.com/gists")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(token));
        var error = assertThrows(GistException.class, () -> client.create(token, body));
        assertEquals("GIST_TOKEN_INVALID", error.getCode());
        assertFalse(error.getMessage().contains(token));
    }
    @Test void permissionFailureIsDistinctFromRateLimit() {
        server.expect(requestTo("https://api.github.com/gists")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("https://api.github.com/gists")).andRespond(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "0"));
        assertEquals("GIST_PERMISSION_REQUIRED", assertThrows(GistException.class, () -> client.create(token, body)).getCode());
        assertEquals(429, assertThrows(GistException.class, () -> client.create(token, body)).getStatus());
    }
    @Test void outagesAreSanitizedAndInvalidFilesNeverSendRequests() {
        assertEquals(400, assertThrows(GistException.class, () -> client.create(token, Map.of())).getStatus());
        server.expect(requestTo("https://api.github.com/gists")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body(token));
        var error = assertThrows(GistException.class, () -> client.create(token, body));
        assertEquals(502, error.getStatus());
        assertFalse(error.getMessage().contains(token));
    }
}
