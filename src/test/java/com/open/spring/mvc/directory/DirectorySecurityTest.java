package com.open.spring.mvc.directory;

import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.MvcSecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DirectorySecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private FilterChainProxy filters;

    @Configuration
    @EnableWebSecurity
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @Import(MvcSecurityConfig.class)
    static class Config {
        @Bean JwtTokenUtil jwtTokenUtil() { return mock(JwtTokenUtil.class); }
        @Bean com.open.spring.mvc.person.PersonJpaRepository personJpaRepository() {
            return mock(com.open.spring.mvc.person.PersonJpaRepository.class);
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        filters = context.getBean(FilterChainProxy.class);
    }

    @AfterEach void close() { context.close(); }

    private MockHttpServletRequest request(String method, String path, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (role != null) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken("tester", "",
                    AuthorityUtils.createAuthorityList(role))));
            request.setSession(session);
        }
        return request;
    }

    @Test void onlyAdministratorsCanRead() throws Exception {
        for (String path : new String[]{"/mvc/data/directory", "/mvc/data/directory/new", "/mvc/data/directory/1/edit"}) {
            MockHttpServletResponse anonymous = new MockHttpServletResponse();
            filters.doFilter(request("GET", path, null), anonymous, (req, res) -> fail("Anonymous access"));
            assertEquals(302, anonymous.getStatus());
            MockHttpServletResponse user = new MockHttpServletResponse();
            filters.doFilter(request("GET", path, "ROLE_USER"), user, (req, res) -> fail("Non-admin access"));
            assertEquals(403, user.getStatus());
            MockHttpServletResponse admin = new MockHttpServletResponse();
            filters.doFilter(request("GET", path, "ROLE_ADMIN"), admin, (req, res) -> res.getWriter().write("allowed"));
            assertEquals("allowed", admin.getContentAsString());
        }
    }

    @Test void mutationsRequireValidSessionToken() throws Exception {
        for (String path : new String[]{"/mvc/data/directory", "/mvc/data/directory/1", "/mvc/data/directory/1/delete"}) {
            MockHttpServletResponse denied = new MockHttpServletResponse();
            filters.doFilter(request("POST", path, "ROLE_ADMIN"), denied, (req, res) -> fail("Missing CSRF accepted"));
            assertEquals(403, denied.getStatus());
        }
        MockHttpServletRequest get = request("GET", "/mvc/data/directory/new", "ROLE_ADMIN");
        String[] token = new String[2];
        filters.doFilter(get, new MockHttpServletResponse(), (req, res) -> {
            CsrfToken csrf = (CsrfToken) req.getAttribute("_csrf");
            token[0] = csrf.getParameterName();
            token[1] = csrf.getToken();
        });
        MockHttpServletRequest post = request("POST", "/mvc/data/directory", null);
        post.setSession(get.getSession());
        post.addParameter(token[0], token[1]);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filters.doFilter(post, allowed, (req, res) -> res.getWriter().write("saved"));
        assertEquals("saved", allowed.getContentAsString());
    }

    @Test void crossOriginRequestsAreRejectedEvenFromApiAllowedOrigins() throws Exception {
        for (String method : new String[]{"GET", "POST", "OPTIONS"}) {
            MockHttpServletRequest crossOrigin = request(method, "/mvc/data/directory", "ROLE_ADMIN");
            crossOrigin.addHeader("Origin", "http://localhost:4500");
            if (method.equals("OPTIONS")) crossOrigin.addHeader("Access-Control-Request-Method", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filters.doFilter(crossOrigin, response, (req, res) -> fail("Cross-origin access"));
            assertEquals(403, response.getStatus());
            assertNull(response.getHeader("Access-Control-Allow-Origin"));
        }
        MockHttpServletRequest sameOrigin = request("GET", "/mvc/data/directory", "ROLE_ADMIN");
        sameOrigin.addHeader("Origin", "http://localhost");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filters.doFilter(sameOrigin, response, (req, res) -> res.getWriter().write("allowed"));
        assertEquals("allowed", response.getContentAsString());
    }

    @Test void loginAndLogoutRequireCsrfTokens() throws Exception {
        for (String path : new String[]{"/login", "/logout"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filters.doFilter(request("POST", path, null), response, (req, res) -> fail("Missing CSRF accepted"));
            assertEquals(403, response.getStatus());
        }
    }

    @Test void anonymousErrorDispatchPreservesTheOriginalError() throws Exception {
        MockHttpServletRequest error = request("POST", "/error", null);
        error.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filters.doFilter(error, response, (req, res) -> res.getWriter().write("error page"));
        assertEquals("error page", response.getContentAsString());
    }
}
