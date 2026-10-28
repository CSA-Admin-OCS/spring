package com.open.spring.mvc.aiconnections;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfigurationSource;

@Component
public class AiRequestGuard {
    private final CorsConfigurationSource source;
    public AiRequestGuard(@Qualifier("corsConfigurationSource") CorsConfigurationSource source) { this.source = source; }
    public void validate(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        var config = source.getCorsConfiguration(request);
        if (!"client".equals(request.getHeader("X-Origin")) || origin == null || config == null
                || config.checkOrigin(origin) == null)
            throw new AiConnectionException(403, "AI_ORIGIN_REJECTED", "Request must come from an authorized OCS page.");
    }
}
