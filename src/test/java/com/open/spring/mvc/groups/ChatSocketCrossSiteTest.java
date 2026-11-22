package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.config.annotation.WebMvcStompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;

import com.open.spring.mvc.directmessages.DirectMessageConversationJpaRepository;
import com.open.spring.mvc.directmessages.DirectMessageTopicGuard;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.SecurityConfig;

import jakarta.servlet.http.Cookie;

/**
 * The real /ws-chat SockJS endpoint, STOMP handler, guard and broker, without a server.
 * <p>
 * Another site can make a signed-in visitor's browser send a GET to a SockJS URL with the
 * login cookie (an img tag, or fetch in no-cors mode) but with no Origin header, and it picks
 * the session id in that URL. It can then send STOMP frames into that session from anywhere.
 * So a session only signs in when the request that opens it comes from a site the REST API
 * trusts, which a browser always marks with an Origin header.
 */
class ChatSocketCrossSiteTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[64]);
    private static final String PAGES = "https://pages.opencodingsociety.com";
    private static final String SUBSCRIBE_TO_HOPS_DM = "[\"CONNECT\\naccept-version:1.2\\nheart-beat:0,0\\n\\n\\u0000\","
            + "\"SUBSCRIBE\\nid:sub-0\\ndestination:/topic/dm/1\\n\\n\\u0000\"]";

    private ThreadPoolTaskScheduler scheduler;
    private ExecutorSubscribableChannel brokerChannel;
    private SimpleBrokerMessageHandler broker;
    private SubProtocolWebSocketHandler stomp;
    private SockJsHttpRequestHandler endpoint;
    private Cookie hopsLogin;

    @BeforeEach
    void setUp() {
        PersonJpaRepository people = mock(PersonJpaRepository.class);
        Person hop = new Person();
        hop.setUid("hop");
        hop.setPassword("hashed");
        hop.setRoles(List.of("ROLE_STUDENT"));
        hop.setTokenVersion(0L);
        when(people.findByUid("hop")).thenReturn(hop);
        PersonDetailsService accounts = new PersonDetailsService();
        ReflectionTestUtils.setField(accounts, "personJpaRepository", people);
        JwtTokenUtil tokens = new JwtTokenUtil();
        ReflectionTestUtils.setField(tokens, "secret", SECRET);
        ReflectionTestUtils.setField(tokens, "personJpaRepository", people);
        ReflectionTestUtils.setField(tokens, "tokenValiditySeconds", 3600L);
        hopsLogin = new Cookie("jwt_java_spring", tokens.generateToken(
                User.withUsername("hop").password("x").authorities("ROLE_STUDENT").build(), List.of("ROLE_STUDENT")));

        DirectMessageConversationJpaRepository conversations = mock(DirectMessageConversationJpaRepository.class);
        when(conversations.existsByIdAndParticipantsUid(1L, "hop")).thenReturn(true);
        DirectMessageTopicGuard guard = new DirectMessageTopicGuard(conversations);

        ExecutorSubscribableChannel clientInbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel clientOutbound = new ExecutorSubscribableChannel();
        brokerChannel = new ExecutorSubscribableChannel();
        clientInbound.addInterceptor(guard);
        broker = new SimpleBrokerMessageHandler(clientInbound, clientOutbound, brokerChannel, List.of("/topic"));
        broker.start();

        stomp = new SubProtocolWebSocketHandler(clientInbound, clientOutbound);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        WebMvcStompEndpointRegistry registry = new WebMvcStompEndpointRegistry(
                stomp, new WebSocketTransportRegistration(), scheduler);
        ChatSocketIdentityInterceptor identity = new ChatSocketIdentityInterceptor(tokens, accounts,
                new SecurityConfig().corsConfigurationSource());
        new WebSocketBrokerConfig(identity, guard).registerStompEndpoints(registry);
        endpoint = (SockJsHttpRequestHandler) ((SimpleUrlHandlerMapping) registry.getHandlerMapping())
                .getUrlMap().get("/ws-chat/**");
        stomp.start();
    }

    @AfterEach
    void tearDown() {
        stomp.stop();
        broker.stop();
        scheduler.shutdown();
    }

    @Test
    void anotherSiteCannotOpenASessionWithAVisitorsLogin() throws Exception {
        // hop's browser, made to load the URL by another site: the cookie, no Origin.
        MockHttpServletRequest crossSiteGet = sockJs("GET", "000/chosenid/eventsource", null);
        crossSiteGet.setCookies(hopsLogin);
        MockHttpServletResponse hopsBrowser = handle(crossSiteGet);

        // That site's own script, anywhere: no cookie, no Origin.
        MockHttpServletRequest send = sockJs("POST", "000/chosenid/xhr_send", null);
        send.setContent(SUBSCRIBE_TO_HOPS_DM.getBytes(StandardCharsets.UTF_8));
        assertEquals(204, handle(send).getStatus());

        broadcastToConversation1("hi hop, this is private");

        assertEquals(200, hopsBrowser.getStatus(), "the stream still opens, signed out");
        assertFalse(hopsBrowser.getContentAsString().contains("this is private"));
    }

    @Test
    void pagesStillGetsDirectMessagesOverTheXhrFallback() throws Exception {
        MockHttpServletRequest open = sockJs("POST", "000/pagessid/xhr_streaming", PAGES);
        open.setCookies(hopsLogin);
        MockHttpServletResponse stream = handle(open);

        MockHttpServletRequest send = sockJs("POST", "000/pagessid/xhr_send", PAGES);
        send.setCookies(hopsLogin);
        send.setContent(SUBSCRIBE_TO_HOPS_DM.getBytes(StandardCharsets.UTF_8));
        assertEquals(204, handle(send).getStatus());

        broadcastToConversation1("hi hop, this is private");

        assertTrue(stream.getContentAsString().contains("this is private"));
    }

    private MockHttpServletResponse handle(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        endpoint.handleRequest(request, response);
        return response;
    }

    /** A request as the SockJS endpoint sees it after Spring MVC routed it. */
    private static MockHttpServletRequest sockJs(String method, String sockJsPath, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ws-chat/" + sockJsPath);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, sockJsPath);
        request.setAsyncSupported(true);
        request.setServerName("spring.opencodingsociety.com");
        request.setContentType("text/plain");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    /** What DirectMessageService's SimpMessagingTemplate does for a new message in conversation 1. */
    private void broadcastToConversation1(String body) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/topic/dm/1");
        brokerChannel.send(MessageBuilder.createMessage(
                ("{\"body\":\"" + body + "\"}").getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders()));
    }
}
