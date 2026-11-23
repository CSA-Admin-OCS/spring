package com.open.spring.mvc.groups;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.open.spring.mvc.directmessages.DirectMessageTopicGuard;

/**
 * Configuration class for setting up a STOMP over WebSocket broker.
 * Implements {@link WebSocketMessageBrokerConfigurer} to customize the
 * message broker and configure registered STOMP endpoints.
 * <p>
 * This configuration enables a simple memory-based message broker to
 * route messages back to the client on destinations prefixed with "/topic",
 * and registers the "/ws-chat" endpoint.
 * <p>
 * When a socket opens, {@link ChatSocketIdentityInterceptor} reads the login cookie. Every frame a
 * client sends then passes {@link DirectMessageTopicGuard}, which lets only a conversation's
 * participants subscribe to its /topic/dm/{id}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketBrokerConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatSocketIdentityInterceptor identityInterceptor;
    private final DirectMessageTopicGuard directMessageTopicGuard;

    public WebSocketBrokerConfig(ChatSocketIdentityInterceptor identityInterceptor,
            DirectMessageTopicGuard directMessageTopicGuard) {
        this.identityInterceptor = identityInterceptor;
        this.directMessageTopicGuard = directMessageTopicGuard;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // "*" lets any site connect signed out. The login cookie only counts from a site in the
        // REST API's CORS list (ChatSocketIdentityInterceptor). The interceptor has to be added
        // before withSockJS(), which copies it to every transport.
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns("*")
                .addInterceptors(identityInterceptor)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(directMessageTopicGuard);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
