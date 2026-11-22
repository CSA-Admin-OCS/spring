package com.open.spring.mvc.groups;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.security.CookieFactory;
import com.open.spring.security.JwtTokenUtil;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the login cookie when a chat socket opens, so the socket knows who is connected.
 * <p>
 * {@code JwtRequestFilter} only runs on {@code /api/**}, so nothing else on {@code /ws-chat}
 * knows the account. This runs the same three checks on the same {@code jwt_java_spring}
 * cookie: the token's signature and expiry, that the account still exists, and that the
 * password hasn't changed since the token was issued. The uid goes in the socket's session
 * attributes, where {@code DirectMessageTopicGuard} reads it.
 * <p>
 * The cookie only counts when the request carries an Origin header from a site the REST API
 * trusts (the {@code corsConfigurationSource} list). A browser adds Origin to every WebSocket
 * handshake, POST and cross-site script request, but another site can make a visitor's browser
 * send a GET with the cookie and no Origin (an img tag, or fetch in no-cors mode). That GET
 * could otherwise open a SockJS session, under an id the other site picked, signed in as the
 * visitor, and the other site could then send frames into it from anywhere.
 * <p>
 * A missing or rejected cookie never refuses the handshake: signed-out visitors still connect
 * to read course chats. The uid is deliberately not made the socket's Principal, because a
 * Principal would also switch on {@code GroupChatWebSocketController}, which ignores every
 * event while there is none.
 */
@Slf4j
@Component
public class ChatSocketIdentityInterceptor implements HandshakeInterceptor {

    /** The session attribute that holds the signed-in uid; absent when signed out. */
    public static final String UID_ATTRIBUTE = "chatSocketUid";

    private final JwtTokenUtil jwtTokenUtil;
    private final PersonDetailsService personDetailsService;
    private final CorsConfigurationSource corsConfigurationSource;

    public ChatSocketIdentityInterceptor(JwtTokenUtil jwtTokenUtil, PersonDetailsService personDetailsService,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.personDetailsService = personDetailsService;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    /** The uid this socket signed in with, or null when it connected signed out. */
    public static String uidOf(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return null;
        }
        return sessionAttributes.get(UID_ATTRIBUTE) instanceof String uid ? uid : null;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest
                && fromTrustedSite(servletRequest.getServletRequest())) {
            loginToken(servletRequest.getServletRequest())
                    .flatMap(this::verifiedUid)
                    .ifPresent(uid -> attributes.put(UID_ATTRIBUTE, uid));
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // Nothing to do once the socket is open.
    }

    /** Whether the request's Origin is one the REST API trusts with the cookie. No Origin means no. */
    private boolean fromTrustedSite(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return false;
        }
        CorsConfiguration trusted = corsConfigurationSource.getCorsConfiguration(request);
        return trusted != null && trusted.checkOrigin(origin) != null;
    }

    /** The first login cookie, the same one JwtRequestFilter would read. */
    private static Optional<String> loginToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> CookieFactory.JWT_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private Optional<String> verifiedUid(String token) {
        try {
            String uid = jwtTokenUtil.getUsernameFromToken(token);
            UserDetails account = personDetailsService.loadUserByUsername(uid);
            return jwtTokenUtil.validateToken(token, account) ? Optional.of(uid) : Optional.empty();
        } catch (RuntimeException ex) {
            // Expired, garbled or forged token, or a deleted account: connect signed out.
            log.debug("Chat socket connected signed out, login cookie not accepted: {}", ex.toString());
            return Optional.empty();
        }
    }
}
