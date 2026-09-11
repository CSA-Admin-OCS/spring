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
        for (String path : new String[]{"/mvc/directory", "/mvc/directory/new", "/mvc/directory/1/edit"}) {
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
        for (String path : new String[]{"/mvc/directory", "/mvc/directory/1", "/mvc/directory/1/delete"}) {
            MockHttpServletResponse denied = new MockHttpServletResponse();
            filters.doFilter(request("POST", path, "ROLE_ADMIN"), denied, (req, res) -> fail("Missing CSRF accepted"));
            assertEquals(403, denied.getStatus());
        }
        MockHttpServletRequest get = request("GET", "/mvc/directory/new", "ROLE_ADMIN");
        String[] token = new String[2];
        filters.doFilter(get, new MockHttpServletResponse(), (req, res) -> {
            CsrfToken csrf = (CsrfToken) req.getAttribute("_csrf");
            token[0] = csrf.getParameterName();
            token[1] = csrf.getToken();
        });
        MockHttpServletRequest post = request("POST", "/mvc/directory", null);
        post.setSession(get.getSession());
        post.addParameter(token[0], token[1]);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filters.doFilter(post, allowed, (req, res) -> res.getWriter().write("saved"));
        assertEquals("saved", allowed.getContentAsString());
    }
}
