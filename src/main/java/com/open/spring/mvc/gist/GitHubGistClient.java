package com.open.spring.mvc.gist;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;

@Service
public class GitHubGistClient {
    private final RestTemplate rest;
    public GitHubGistClient() {
        var timeout = Timeout.ofSeconds(10);
        var config = RequestConfig.custom().setConnectTimeout(timeout).setResponseTimeout(timeout)
            .setConnectionRequestTimeout(timeout).build();
        rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(
            HttpClients.custom().setDefaultRequestConfig(config).disableRedirectHandling().build()));
    }
    GitHubGistClient(RestTemplate rest) { this.rest = rest; }
    public String validateIdentity(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_]{20,512}")) {
            throw new GistException(400, "GIST_TOKEN_INVALID", "Enter a valid GitHub personal access token.");
        }
        var response = request("/user", HttpMethod.GET, token, null);
        String scopes = response.getHeaders().getFirst("X-OAuth-Scopes");
        if (scopes != null && Arrays.stream(scopes.split(",")).map(String::trim).noneMatch("gist"::equals)) {
            throw new GistException(403, "GIST_PERMISSION_REQUIRED", "Token requires the gist scope.");
        }
        Object username = response.getBody() == null ? null : response.getBody().get("login");
        if (!(username instanceof String value) || !value.matches("[A-Za-z0-9-]{1,39}")) throw invalidResponse();
        return value;
    }
    public String create(String token, Map<String, Object> body) {
        if (!(body.get("files") instanceof Map<?, ?> files) || files.isEmpty() || files.size() > 100) {
            throw new GistException(400, "GIST_FILES_INVALID", "Provide between 1 and 100 code files.");
        }
        long total = 0;
        for (var entry : files.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank() || name.length() > 255
                    || !(entry.getValue() instanceof Map<?, ?> file) || !(file.get("content") instanceof String content)) {
                throw new GistException(400, "GIST_FILES_INVALID", "Each code file needs a filename and text content.");
            }
            total += content.getBytes(StandardCharsets.UTF_8).length;
        }
        if (total > 2_000_000) throw new GistException(413, "GIST_FILES_TOO_LARGE", "Code export exceeds 2 MB.");
        Object description = body.getOrDefault("description", "Exported from Open Coding Society");
        if (!(description instanceof String text) || text.length() > 1000) {
            throw new GistException(400, "GIST_DESCRIPTION_INVALID", "Use a description of at most 1000 characters.");
        }
        var response = request("/gists", HttpMethod.POST, token, Map.of("files", files, "description", text, "public", false));
        Object url = response.getBody() == null ? null : response.getBody().get("html_url");
        if (!(url instanceof String value) || !value.startsWith("https://gist.github.com/")) throw invalidResponse();
        return value;
    }
    public Map<?, ?> read(String id, String readToken) {
        if (id == null || !id.matches("[a-fA-F0-9]{6,64}")) throw new GistException(400, "GIST_ID_INVALID", "Invalid Gist ID.");
        ResponseEntity<Map> response;
        try { response = request("/gists/" + id, HttpMethod.GET, readToken, null); }
        catch (GistException e) {
            // An obsolete optional server read credential must not break unlisted links.
            if (readToken == null || readToken.isBlank()
                || !(e.getCode().equals("GIST_TOKEN_INVALID") || e.getCode().equals("GIST_PERMISSION_REQUIRED"))) throw e;
            response = request("/gists/" + id, HttpMethod.GET, null, null);
        }
        if (response.getBody() == null) throw invalidResponse();
        return response.getBody();
    }
    private ResponseEntity<Map> request(String path, HttpMethod method, String token, Object body) {
        var headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isBlank()) headers.setBearerAuth(token);
        try {
            var response = rest.exchange("https://api.github.com" + path, method, new HttpEntity<>(body, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) throw invalidResponse();
            return response;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            var responseHeaders = e.getResponseHeaders();
            if (status == 429 || (status == 403 && responseHeaders != null
                && ("0".equals(responseHeaders.getFirst("X-RateLimit-Remaining")) || responseHeaders.containsKey("Retry-After")))) {
                throw new GistException(429, "GITHUB_RATE_LIMITED", "GitHub request limit reached. Try again later.");
            }
            if (status == 401) throw new GistException(403, "GIST_TOKEN_INVALID", "GitHub token expired or was revoked. Replace it in Profile Settings.");
            if (status == 403) throw new GistException(403, "GIST_PERMISSION_REQUIRED", "GitHub token needs Gists write permission. Update it in Profile Settings.");
            if (status == 404) throw new GistException(404, "GIST_NOT_FOUND", "Gist not found.");
            if (status == 422) throw new GistException(400, "GIST_FILES_INVALID", "GitHub rejected the Gist files.");
            throw new GistException(502, "GITHUB_UNAVAILABLE", "GitHub is unavailable. Try again later.");
        } catch (RestClientException e) {
            // Never expose exception bodies, which could contain a credential.
            throw new GistException(502, "GITHUB_UNAVAILABLE", "GitHub could not be reached. Try again later.");
        }
    }
    private GistException invalidResponse() {
        return new GistException(502, "GITHUB_INVALID_RESPONSE", "GitHub returned an unexpected response.");
    }
}
