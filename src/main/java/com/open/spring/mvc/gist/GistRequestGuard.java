package com.open.spring.mvc.gist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfigurationSource;

@Component
public class GistRequestGuard {
    private final CorsConfigurationSource source;
    public GistRequestGuard(@org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource")
            CorsConfigurationSource source) { this.source = source; }
    public void validate(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        var config = source.getCorsConfiguration(request);
        if (!"client".equals(request.getHeader("X-Origin")) || origin == null || config == null || config.checkOrigin(origin) == null)
            throw new GistException(403, "GIST_ORIGIN_REJECTED", "Request must come from an authorized OCS page.");
    }
}
