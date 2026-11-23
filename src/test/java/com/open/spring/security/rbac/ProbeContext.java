package com.open.spring.security.rbac;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRoleJpaRepository;
import com.open.spring.security.CookieFactory;
import com.open.spring.security.JwtAuthenticationEntryPoint;
import com.open.spring.security.JwtRequestFilter;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.MvcSecurityConfig;
import com.open.spring.security.RateLimitFilter;
import com.open.spring.security.SecurityConfig;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The application's two real filter chains, built in a small context, and a way to ask them what each kind of caller
 * gets past them.
 *
 * <p>The context holds the real {@link SecurityConfig} and {@link MvcSecurityConfig}, the real JWT filter, token
 * class and cookie class, and one stand-in controller that answers "reached" for every path and method. Nothing else
 * of the application is started: no database, no controllers, no outbound client. So what is asked here is only the
 * filter chains' decision: did the request get through to a controller, or was it refused or sent to log in.
 * {@code @PreAuthorize} and checks inside handlers are not run.
 *
 * <p>Two things differ from the running application, on purpose. The rate limiter is switched off, because it decides
 * how many requests a caller may make, not who may make one. And people are not loaded from a database: a token's
 * subject carries the roles the caller is to have.
 */
final class ProbeContext implements AutoCloseable {

    /** The kinds of caller. The roles are the ones Spring's code uses; the first four hold no usable role. */
    enum Caller {
        ANONYMOUS(null), INVALID_TOKEN(null), EXPIRED_TOKEN("ROLE_USER"), NO_ROLE(null),
        USER("ROLE_USER"), STUDENT("ROLE_STUDENT"), MENTOR("ROLE_MENTOR"), TEACHER("ROLE_TEACHER"),
        ADMIN("ROLE_ADMIN"), ASSIGNMENT_SYNC("ROLE_ASSIGNMENT_SYNC");

        final String role;

        Caller(String role) {
            this.role = role;
        }

        /** A caller who holds one of the application's roles and is signed in. */
        boolean hasRole() {
            return this != EXPIRED_TOKEN && role != null;
        }
    }

    /** Not a real key: it signs tokens only inside this test context. */
    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZg==";
    private static final String JWT_COOKIE = "jwt_java_spring";

    static {
        // the JWT filter writes a warning for every request: thousands of lines that say nothing here
        if (org.slf4j.LoggerFactory.getLogger(JwtRequestFilter.class) instanceof ch.qos.logback.classic.Logger logger) {
            logger.setLevel(ch.qos.logback.classic.Level.ERROR);
        }
    }

    private final AnnotationConfigWebApplicationContext context;
    private final MockMvc mvc;
    private final JwtTokenUtil jwt;
    private final FilterChainProxy chains;

    static ProbeContext real() {
        return new ProbeContext(RealChains.class);
    }

    static ProbeContext fixture() {
        return new ProbeContext(FixtureChains.class);
    }

    private ProbeContext(Class<?> config) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe",
                Map.of("jwt.secret", TEST_SECRET, "jwt.cookie.secure", "false")));
        context.register(config);
        context.refresh();
        chains = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters((Filter) chains).build();
        jwt = context.getBean(JwtTokenUtil.class);
    }

    @Override
    public void close() {
        context.close();
    }

    /** How many controller beans the context holds: one, the stand-in, or the probe is reaching real code. */
    int controllerBeans() {
        return context.getBeansWithAnnotation(org.springframework.stereotype.Controller.class).size();
    }

    /** A path that matches the pattern: every variable and wildcard filled with something. */
    static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^}]*}", "1").replace("**", "x").replace("*", "x");
    }

    /** Which chain's filters take this path: "api" when the JWT filter is among them, "mvc" when it is not, "none" when no chain matches. */
    String chainOf(String path) {
        List<Filter> filters = chains.getFilters(path);
        if (filters.isEmpty()) {
            return "none";
        }
        return filters.stream().anyMatch(JwtRequestFilter.class::isInstance) ? "api" : "mvc";
    }

    /**
     * What the chains do with this request from this caller: "reached" (it got through to a controller), "401" or "403"
     * (refused), "redirect:/login" (sent to the login page), or "status:NNN" for anything else, which is a surprise.
     */
    String ask(String method, String path, Caller caller) {
        try {
            MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
            switch (caller) {
                case ANONYMOUS -> { }
                case INVALID_TOKEN -> builder.cookie(new Cookie(JWT_COOKIE, "not.a.token"));
                case EXPIRED_TOKEN -> builder.cookie(new Cookie(JWT_COOKIE, expiredToken()));
                default -> builder.cookie(new Cookie(JWT_COOKIE, token(caller))).session(session(caller));
            }
            MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
            int status = response.getStatus();
            if (status == 200 && "reached".equals(response.getContentAsString())) {
                return "reached";
            }
            if (status == 401 || status == 403) {
                return String.valueOf(status);
            }
            if (status == 302) {
                String location = response.getHeader("Location");
                return "redirect:" + (location == null ? "" : URI.create(location).getPath());
            }
            return "status:" + status;
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private static List<GrantedAuthority> authorities(Caller caller) {
        List<GrantedAuthority> out = new ArrayList<>();
        if (caller.hasRole()) {
            out.add(new SimpleGrantedAuthority(caller.role));
        }
        return out;
    }

    private static String subject(Caller caller) {
        // the stand-in people service reads the roles from what follows the colon
        return "who:" + (caller.hasRole() ? caller.role : "");
    }

    private String token(Caller caller) {
        return jwt.generateToken(new User(subject(caller), "x", authorities(caller)), caller.hasRole() ? List.of(caller.role) : List.of());
    }

    private String expiredToken() {
        Object before = ReflectionTestUtils.getField(jwt, "tokenValiditySeconds");
        try {
            ReflectionTestUtils.setField(jwt, "tokenValiditySeconds", -3600L);
            return jwt.generateToken(new User("who:ROLE_USER", "x", List.of(new SimpleGrantedAuthority("ROLE_USER"))), List.of("ROLE_USER"));
        } finally {
            ReflectionTestUtils.setField(jwt, "tokenValiditySeconds", before);
        }
    }

    /** A browser that is signed in: the MVC chain reads the session, the API chain reads the cookie, so both are sent. */
    private static MockHttpSession session(Caller caller) {
        SecurityContext holder = SecurityContextHolder.createEmptyContext();
        holder.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new User(subject(caller), "x", authorities(caller)), null, authorities(caller)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, holder);
        return session;
    }

    // ---------------------------------------------------------------- what the context is made of

    /** The parts both chains need that are not the chains themselves. */
    @Configuration
    abstract static class Beans {
        @Bean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
            return new JwtAuthenticationEntryPoint();
        }

        @Bean JwtRequestFilter jwtRequestFilter() {
            return new JwtRequestFilter();
        }

        @Bean RateLimitFilter rateLimitFilter() {
            return new RateLimitFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return true;  // how many requests, not who may make one
                }
            };
        }

        @Bean CookieFactory cookieFactory() {
            return new CookieFactory();
        }

        @Bean JwtTokenUtil jwtTokenUtil() {
            return new JwtTokenUtil();
        }

        @Bean PersonJpaRepository personJpaRepository() {
            return mock(PersonJpaRepository.class);
        }

        @Bean PersonRoleJpaRepository personRoleJpaRepository() {
            return mock(PersonRoleJpaRepository.class);
        }

        @Bean PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean PersonDetailsService personDetailsService() {
            PersonDetailsService service = mock(PersonDetailsService.class);
            // the roles are written after the colon of the name: "who:ROLE_ADMIN", or "who:" for a person with none
            when(service.loadUserByUsername(anyString())).thenAnswer(call -> {
                String name = call.getArgument(0);
                List<GrantedAuthority> roles = new ArrayList<>();
                String[] parts = name.split(":", 2);
                if (parts.length == 2 && !parts[1].isBlank()) {
                    for (String role : parts[1].split(",")) {
                        roles.add(new SimpleGrantedAuthority(role));
                    }
                }
                return new User(name, "x", roles);
            });
            return service;
        }

        @Bean Stub stub() {
            return new Stub();
        }
    }

    /** Answers "reached" to every path and method: a request that gets this far got past the filter chains. */
    @RestController
    static class Stub {
        @RequestMapping("/**")
        String reached() {
            return "reached";
        }
    }

    /** This application's two chains, as they are. */
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, MvcSecurityConfig.class})
    static class RealChains extends Beans {
    }

    /** Two small chains with rules written to have known answers, for checking the prober itself. */
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class FixtureChains extends Beans {
        @Bean
        @Order(1)
        SecurityFilterChain api(HttpSecurity http, JwtRequestFilter jwtFilter, JwtAuthenticationEntryPoint entryPoint) throws Exception {
            http.securityMatcher("/api/**")
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/api/open/**").permitAll()
                            .requestMatchers("/api/signed/**").authenticated()
                            .requestMatchers("/api/staff/**").hasAnyAuthority("ROLE_TEACHER", "ROLE_ADMIN")
                            .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                            .requestMatchers("/api/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN", "ROLE_TEACHER", "ROLE_STUDENT"))
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler((request, response, denied) -> response.setStatus(403)))
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain pages(HttpSecurity http) throws Exception {
            http.securityMatcher("/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/page/open").permitAll()
                            .requestMatchers("/page/staff").hasAuthority("ROLE_TEACHER")
                            .anyRequest().authenticated())
                    .formLogin(form -> form.loginPage("/login").permitAll())
                    .exceptionHandling(Customizer.withDefaults());
            return http.build();
        }
    }
}
