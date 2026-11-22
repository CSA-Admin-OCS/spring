package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.SecurityConfig;

import jakarta.servlet.http.Cookie;

/**
 * The chat socket learns who is connecting from the same login cookie, and the same checks,
 * as the REST API, and only when the request comes from a site the REST API trusts. A cookie
 * REST would reject leaves the socket signed out, and no cookie ever stops the socket from
 * connecting (signed-out visitors still read course chats).
 */
class ChatSocketIdentityInterceptorTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[64]);
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString("x".repeat(64).getBytes());
    private static final String PAGES = "https://pages.opencodingsociety.com";
    private static final String LOCAL_PAGES = "http://localhost:4500";

    private PersonJpaRepository people;
    private Person hop;
    private ChatSocketIdentityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        people = mock(PersonJpaRepository.class);
        hop = new Person();
        hop.setUid("hop");
        hop.setPassword("hashed");
        hop.setRoles(List.of("ROLE_STUDENT"));
        hop.setTokenVersion(0L);
        when(people.findByUid("hop")).thenReturn(hop);

        PersonDetailsService accounts = new PersonDetailsService();
        ReflectionTestUtils.setField(accounts, "personJpaRepository", people);
        interceptor = new ChatSocketIdentityInterceptor(tokenUtil(SECRET, 3600), accounts,
                new SecurityConfig().corsConfigurationSource());
    }

    @Test
    void validLoginCookieFromPagesRecordsTheUid() {
        String token = tokenFor("hop", SECRET, 3600);

        assertEquals("hop", ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie(token))));
        assertEquals("hop", ChatSocketIdentityInterceptor.uidOf(handshake(LOCAL_PAGES, loginCookie(token))));
    }

    /** What another site gets from an img tag or a no-cors fetch: the browser adds the cookie but no Origin. */
    @Test
    void loginCookieWithoutAnOriginConnectsSignedOut() {
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(null, loginCookie(tokenFor("hop", SECRET, 3600)))));
    }

    @Test
    void loginCookieFromAnUntrustedSiteConnectsSignedOut() {
        String token = tokenFor("hop", SECRET, 3600);

        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake("https://evil.example", loginCookie(token))));
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake("null", loginCookie(token))));
    }

    @Test
    void noLoginCookieConnectsSignedOut() {
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES)));
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, new Cookie("sess_java_spring", "abc123"))));
    }

    @Test
    void garbledOrForgedTokenConnectsSignedOut() {
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie("not-a-jwt"))));
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie(tokenFor("hop", OTHER_SECRET, 3600)))));
    }

    @Test
    void expiredTokenConnectsSignedOut() {
        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie(tokenFor("hop", SECRET, -60)))));
    }

    @Test
    void tokenFromBeforeAPasswordChangeConnectsSignedOut() {
        String token = tokenFor("hop", SECRET, 3600);
        hop.setTokenVersion(1L); // a password change bumps the version

        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie(token))));
    }

    @Test
    void deletedAccountConnectsSignedOut() {
        String token = tokenFor("hop", SECRET, 3600);
        when(people.findByUid("hop")).thenReturn(null);

        assertNull(ChatSocketIdentityInterceptor.uidOf(handshake(PAGES, loginCookie(token))));
    }

    /** Runs the handshake and checks the one rule that holds for every request: it is never refused. */
    private Map<String, Object> handshake(String origin, Cookie... cookies) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws-chat/123/abcdefgh/websocket");
        if (origin != null) {
            servletRequest.addHeader("Origin", origin);
        }
        if (cookies.length > 0) {
            servletRequest.setCookies(cookies);
        }
        Map<String, Object> attributes = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);
        assertTrue(allowed, "the chat socket must connect with or without a login");
        return attributes;
    }

    private static Cookie loginCookie(String token) {
        return new Cookie("jwt_java_spring", token);
    }

    private String tokenFor(String uid, String secret, long validitySeconds) {
        return tokenUtil(secret, validitySeconds).generateToken(
                User.withUsername(uid).password("x").authorities("ROLE_STUDENT").build(),
                List.of("ROLE_STUDENT"));
    }

    private JwtTokenUtil tokenUtil(String secret, long validitySeconds) {
        JwtTokenUtil util = new JwtTokenUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "personJpaRepository", people);
        ReflectionTestUtils.setField(util, "tokenValiditySeconds", validitySeconds);
        return util;
    }
}
