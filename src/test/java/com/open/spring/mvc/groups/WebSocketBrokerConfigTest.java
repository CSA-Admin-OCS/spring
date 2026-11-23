package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.config.annotation.WebMvcStompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;

import com.open.spring.mvc.directmessages.DirectMessageConversationJpaRepository;
import com.open.spring.mvc.directmessages.DirectMessageTopicGuard;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.security.JwtTokenUtil;
import com.open.spring.security.SecurityConfig;

/**
 * The DM checks only work while the socket config runs them: every unit test of the guard and
 * the login check would still pass if this wiring were dropped, and the topics would be open again.
 */
class WebSocketBrokerConfigTest {

    private final ChatSocketIdentityInterceptor identity = new ChatSocketIdentityInterceptor(
            mock(JwtTokenUtil.class), mock(PersonDetailsService.class), new SecurityConfig().corsConfigurationSource());
    private final DirectMessageTopicGuard guard =
            new DirectMessageTopicGuard(mock(DirectMessageConversationJpaRepository.class));
    private final WebSocketBrokerConfig config = new WebSocketBrokerConfig(identity, guard);

    @Test
    void everySockJsTransportOnTheChatEndpointReadsTheLogin() {
        TransportHandlingSockJsService sockJs = assertInstanceOf(TransportHandlingSockJsService.class,
                chatEndpoint().getSockJsService());

        assertTrue(sockJs.getHandshakeInterceptors().contains(identity),
                "the login check must run on the SockJS service, which every transport goes through");
    }

    /**
     * SockJS's iframe page runs on the socket's own origin, which is on the CORS list, so a site
     * that could embed it would pass the login check's Origin test. Spring turns the iframe off
     * while allowed origin patterns are set; setAllowedOrigins("*") would turn it back on.
     */
    @Test
    void theSockJsIframePageStaysOff() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws-chat/iframe.html");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "iframe.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        chatEndpoint().handleRequest(request, response);

        assertEquals(404, response.getStatus());
    }

    @Test
    void everyClientFrameGoesThroughTheDmGuard() {
        InspectableChannelRegistration inbound = new InspectableChannelRegistration();

        config.configureClientInboundChannel(inbound);

        assertEquals(List.of(guard), inbound.interceptors());
    }

    /** The /ws-chat endpoint as this config registers it on Spring's real STOMP endpoint registry. */
    private SockJsHttpRequestHandler chatEndpoint() {
        WebMvcStompEndpointRegistry registry = new WebMvcStompEndpointRegistry(
                new SubProtocolWebSocketHandler(new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel()),
                new WebSocketTransportRegistration(),
                new ThreadPoolTaskScheduler());
        config.registerStompEndpoints(registry);
        SimpleUrlHandlerMapping mapping = assertInstanceOf(SimpleUrlHandlerMapping.class, registry.getHandlerMapping());
        return assertInstanceOf(SockJsHttpRequestHandler.class, mapping.getUrlMap().get("/ws-chat/**"));
    }

    /** ChannelRegistration keeps its interceptors protected; this only reads them. */
    private static final class InspectableChannelRegistration extends ChannelRegistration {
        List<ChannelInterceptor> interceptors() {
            return getInterceptors();
        }
    }
}
