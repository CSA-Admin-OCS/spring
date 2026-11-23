package com.open.spring.mvc.calls;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Lets only the OCS pages call /api/calls, the same check as UiBuilderRequestGuard. A request must
 * say X-Origin: client, come from one of the exact origins in calls.allowed-origins (not the CORS
 * patterns, which accept every *.opencodingsociety.com student server that would also receive the
 * login cookie), and send its body as application/json when it writes. Reads are checked too.
 */
@Component
public class CallRequestGuard {

    private final Set<String> allowedOrigins;

    public CallRequestGuard(CallsProperties properties) {
        this.allowedOrigins = properties.getAllowedOrigins().stream()
                .map(CallRequestGuard::normalize)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Throws 403 {"reason":"origin_rejected"} unless the request came from an OCS page. */
    public void check(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        boolean fromClient = "client".equals(request.getHeader("X-Origin"));
        boolean knownOrigin = origin != null && allowedOrigins.contains(normalize(origin));
        boolean jsonIfWriting = !isWrite(request.getMethod()) || isJson(request.getContentType());
        if (!(fromClient && knownOrigin && jsonIfWriting)) {
            throw new CallException(HttpStatus.FORBIDDEN, "origin_rejected");
        }
    }

    private static boolean isWrite(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    // Scheme and host are case-insensitive; browsers never send a trailing slash, but a configured value might.
    private static String normalize(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
