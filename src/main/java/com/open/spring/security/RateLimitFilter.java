package com.open.spring.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${security.rate-limit.requests-per-minute:1000}")
    private int requestsPerMinute;

    @Value("${security.rate-limit.admin-requests-per-minute:5000}")
    private int adminRequestsPerMinute;

    @Value("${security.rate-limit.exempt-paths:}")
    private String exemptPaths;

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();
    private final Map<String, Integer> userLimits = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeUsers = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI();

        return Set.of(exemptPaths.split(","))
                .stream()
                .map(String::trim)
                .anyMatch(requestPath::equals);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String username = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : request.getRemoteAddr();

        // Track logged-in users
        if (request.getUserPrincipal() != null) {
            activeUsers.put(username, true);
        }

        // Check if user is an admin
        boolean isAdmin = isAdminUser();

        int activeUserCount = activeUsers.size();
        int dynamicLimit = isAdmin ? adminRequestsPerMinute : computeDynamicLimit(username, activeUserCount);

        // Check if limit has changed for this user
        Integer previousLimit = userLimits.get(username);
        if (previousLimit == null || previousLimit != dynamicLimit) {
            // Recreate bucket with new limit
            cache.put(username, createBucketWithLimit(dynamicLimit));
            userLimits.put(username, dynamicLimit);
        }

        Bucket bucket = cache.get(username);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.getWriter().write("Too many requests - limit: " + dynamicLimit + " per minute");
        }
    }

    private Bucket createBucketWithLimit(int limit) {
        // Use Bandwidth.builder() instead of Bandwidth.classic()
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillGreedy(limit, Duration.ofMinutes(1))
                .build();

        return Bucket.builder()
                .addLimit(bandwidth)
                .build();
    }

    private int computeDynamicLimit(String username, int activeUsers) {
        if (activeUsers < 5) {
            return 100;
        } else if (activeUsers < 20) {
            return 300;
        } else {
            return 600;
        }
    }

    private boolean isAdminUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
