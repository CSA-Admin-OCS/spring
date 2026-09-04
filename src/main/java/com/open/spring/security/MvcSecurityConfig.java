package com.open.spring.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;

/*
 * MvcSecurityConfig.java
 * 
 * MVC Security Configuration - handles web pages and form-based login
 * 
 * Key Points:
 * - Order(2): Processed AFTER the API security chain (Order 1)
 * - Matches: All requests not handled by API chain (/**)
 * - Authentication: Traditional form login with sessions
 * - Login: /login page, redirects to /mvc/person/read on success
 * - Logout: Deletes session cookie, redirects to homepage
 * 
 * Access Levels:
 * - permitAll(): /login, /mvc/person/create, /mvc/person/reset
 * - authenticated(): Most /mvc/** endpoints
 * - ROLE_ADMIN: /mvc/person/delete, /mvc/extract, /mvc/import
 * - ROLE_TEACHER/STUDENT: /mvc/synergy/** endpoints
 * 
 * For API security (JWT-based), see SecurityConfig.java
 */

@Configuration
public class MvcSecurityConfig {

    private static final String JWT_COOKIE_NAME = "jwt_java_spring";
    private static final String JWT_COOKIE_PATH = "/api";

    @Value("${jwt.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${jwt.cookie.same-site:None}")
    private String cookieSameSite;

    @Value("${server.servlet.session.cookie.name:sess_java_spring}")
    private String sessionCookieName;

    @Value("${server.servlet.session.cookie.secure:true}")
    private boolean sessionCookieSecure;

    @Value("${server.servlet.session.cookie.same-site:None}")
    private String sessionCookieSameSite;

    @Value("${server.servlet.session.cookie.domain:}")
    private String sessionCookieDomain;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    /**
     * MVC security: form login, session-based.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain mvcSecurityFilterChain(HttpSecurity http) throws Exception {

        http
            // Everything that is NOT handled by the API chain
            .securityMatcher("/**")
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mvc/person/search/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/mvc/person/create").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/create").permitAll()
                .requestMatchers(HttpMethod.GET, "/mvc/person/reset").permitAll()
                .requestMatchers(HttpMethod.GET, "/mvc/person/reset/check").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/start").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/check").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/oauth/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/oauth/complete").permitAll()
                // Must be public: raised by a user who's rate-limited and, by definition,
                // not logged in. /reset/ticket/{id}/grant is deliberately NOT here -- it
                // falls through to anyRequest().authenticated() + the controller's own
                // ROLE_ADMIN check below, same as /mvc/person/reset/admin/{id}.
                .requestMatchers(HttpMethod.POST, "/mvc/person/reset/ticket").permitAll()
                .requestMatchers("/mvc/person/read/**").authenticated()
                .requestMatchers("/mvc/person/cookie-clicker").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/person/update/user").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/person/update/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/person/update").authenticated()
                .requestMatchers(HttpMethod.POST,"/mvc/person/update/role").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/person/update/roles").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/person/delete/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/bathroom/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/login").permitAll()
                .requestMatchers("/mvc/synergy/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/mvc/synergy/gradebook").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN", "ROLE_STUDENT")
                .requestMatchers(HttpMethod.GET, "/mvc/synergy/view-grade-requests").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/mvc/assignments/tracker").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/mvc/teamteach/teachergrading").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET,"/mvc/train/**").authenticated()
                .requestMatchers(HttpMethod.GET,"/mvc/extract/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/extract/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST,"/mvc/import/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/grades/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/mvc/assignments/read").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                .requestMatchers("/mvc/bank/read").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.OPTIONS, "/ws-chat/**").permitAll()
                .requestMatchers("/mvc/progress/read").hasAnyAuthority("ROLE_ADMIN", "ROLE_TEACHER")
                .requestMatchers("/ws-chat/**").permitAll()
                .requestMatchers("/run/**").permitAll()  // Java runner endpoints - public access
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler((request, response, authentication) -> {
                    if (authentication == null || !authentication.isAuthenticated()) {
                        response.sendRedirect("/login?error");
                        return;
                    }

                    UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                    List<String> roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());

                    String token = jwtTokenUtil.generateToken(userDetails, roles);
                    if (token == null) {
                        response.sendError(500, "Token generation failed");
                        return;
                    }

                    // Build JWT cookie with domain support for cross-subdomain requests
                    ResponseCookie.ResponseCookieBuilder jwtCookieBuilder = ResponseCookie.from(JWT_COOKIE_NAME, token)
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .path(JWT_COOKIE_PATH)
                        .maxAge(-1)
                        .sameSite(cookieSameSite);

                    applyJwtCookieScope(jwtCookieBuilder);
                    
                    ResponseCookie jwtCookie = jwtCookieBuilder.build();

                    response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
                    response.sendRedirect("/mvc/person/read");
                }))
            .logout(logout -> logout
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .logoutSuccessHandler((request, response, authentication) -> {
                    ResponseCookie.ResponseCookieBuilder sessionCookieBuilder = ResponseCookie.from(sessionCookieName, "")
                        .httpOnly(true)
                        .secure(sessionCookieSecure)
                        .path("/")
                        .maxAge(0)
                        .sameSite(sessionCookieSameSite);
                    if (!sessionCookieDomain.isBlank()) {
                        sessionCookieBuilder.domain(sessionCookieDomain);
                    }
                    ResponseCookie sessionCookie = sessionCookieBuilder.build();

                    ResponseCookie.ResponseCookieBuilder jwtCookieBuilder = ResponseCookie.from(JWT_COOKIE_NAME, "")
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .path(JWT_COOKIE_PATH)
                        .maxAge(0)
                        .sameSite(cookieSameSite);
                    applyJwtCookieScope(jwtCookieBuilder);
                    ResponseCookie jwtCookie = jwtCookieBuilder.build();
                    ResponseCookie jwtHostOnlyCookie = ResponseCookie.from(JWT_COOKIE_NAME, "")
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .path(JWT_COOKIE_PATH)
                        .maxAge(0)
                        .sameSite(cookieSameSite)
                        .build();

                    if (!cookieSecure) {
                        // Cleanup for legacy local dev cookies that were created with Domain=localhost.
                        ResponseCookie jwtLegacyLocalhostCookie = ResponseCookie.from(JWT_COOKIE_NAME, "")
                            .httpOnly(true)
                            .secure(false)
                            .path(JWT_COOKIE_PATH)
                            .maxAge(0)
                            .sameSite(cookieSameSite)
                            .domain("localhost")
                            .build();
                        response.addHeader(HttpHeaders.SET_COOKIE, jwtLegacyLocalhostCookie.toString());
                    }

                    response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.toString());
                    response.addHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
                    response.addHeader(HttpHeaders.SET_COOKIE, jwtHostOnlyCookie.toString());
                    response.sendRedirect("/login?logout");
                }));

        return http.build();
    }

    @Bean(name = "mvcEndpointRolePolicy")
    public Map<String, String> mvcEndpointRolePolicy() {
        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("GET/POST /login", "permitAll");
        policy.put("GET/POST /mvc/person/create", "permitAll");
        policy.put("GET /mvc/person/reset", "permitAll");
        policy.put("GET /mvc/person/reset/check", "permitAll");
        policy.put("POST /mvc/person/reset/start", "permitAll");
        policy.put("POST /mvc/person/reset/check", "permitAll");
        policy.put("POST /mvc/person/reset/oauth/verify", "permitAll");
        policy.put("POST /mvc/person/reset/oauth/complete", "permitAll");
        policy.put("POST /mvc/person/reset/ticket", "permitAll");
        policy.put("POST /mvc/person/reset/ticket/{id}/grant", "authenticated + ROLE_ADMIN (controller check)");
        policy.put("GET /mvc/person/update/user", "authenticated");
        policy.put("POST /mvc/person/update", "authenticated (+ controller ownership checks)");
        policy.put("POST /mvc/person/update/role", "ROLE_ADMIN");
        policy.put("POST /mvc/person/update/roles", "ROLE_ADMIN");
        policy.put("/mvc/person/delete/**", "ROLE_ADMIN");
        return Map.copyOf(policy);
    }

    private void applyJwtCookieScope(ResponseCookie.ResponseCookieBuilder cookieBuilder) {
        if (cookieSecure) {
            cookieBuilder.domain(".opencodingsociety.com");
        }
    }
}
