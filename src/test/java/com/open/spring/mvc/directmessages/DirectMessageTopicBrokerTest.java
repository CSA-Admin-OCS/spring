package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.DefaultSimpUserRegistry;

import com.open.spring.mvc.groups.ChatSocketIdentityInterceptor;

/**
 * The guard in front of the two handlers @EnableWebSocketMessageBroker puts on the client
 * inbound channel: Spring's real simple broker and its user destination handler. Before the
 * guard, anyone's subscription got every DM: by its id, or with a wildcard like /topic/** that
 * needs no id at all. And anyone's SEND to a DM topic reached the people in it.
 */
class DirectMessageTopicBrokerTest {

    private final List<Message<?>> delivered = new ArrayList<>();
    private int subscriptionCount;
    private ExecutorSubscribableChannel clientInbound;
    private ExecutorSubscribableChannel brokerChannel;
    private SimpleBrokerMessageHandler broker;
    private UserDestinationMessageHandler userDestinations;

    @BeforeEach
    void setUp() {
        DirectMessageConversationJpaRepository conversations = mock(DirectMessageConversationJpaRepository.class);
        when(conversations.existsByIdAndParticipantsUid(1L, "hop")).thenReturn(true);

        clientInbound = new ExecutorSubscribableChannel(); // no executor, so delivery is synchronous
        clientInbound.addInterceptor(new DirectMessageTopicGuard(conversations));
        ExecutorSubscribableChannel clientOutbound = new ExecutorSubscribableChannel();
        clientOutbound.subscribe(delivered::add);
        brokerChannel = new ExecutorSubscribableChannel();

        broker = new SimpleBrokerMessageHandler(clientInbound, clientOutbound, brokerChannel, List.of("/topic"));
        userDestinations = new UserDestinationMessageHandler(clientInbound, brokerChannel,
                new DefaultUserDestinationResolver(new DefaultSimpUserRegistry()));
        broker.start();
        userDestinations.start();
    }

    @AfterEach
    void tearDown() {
        userDestinations.stop();
        broker.stop();
    }

    /**
     * Spring turns SUBSCRIBE /user/X into a subscription to X + "-user" + the session id, which a
     * SockJS client picks itself. Session id "}" closes the brace this destination opens, so the
     * subscription becomes the wildcard /topic/dm/{x:1|-user}, which matches /topic/dm/1.
     */
    @Test
    void aUserDestinationCannotBecomeADmWildcard() {
        connect("hop-socket");
        connect("}");
        subscribe("hop-socket", "hop", "/topic/dm/1");
        subscribe("}", null, "/user/topic/dm/{x:1|");

        serverBroadcast("/topic/dm/1", "{\"body\":\"hi hop, this is private\"}");

        assertEquals(List.of("hop-socket"), socketsThatReceivedSomething());
    }

    @Test
    void onlyTheParticipantReceivesTheBroadcast() {
        connect("hop-socket");
        connect("outsider-socket");
        subscribe("hop-socket", "hop", "/topic/dm/1");
        subscribe("outsider-socket", null, "/topic/dm/1");
        subscribe("outsider-socket", null, "/topic/dm/*");
        subscribe("outsider-socket", null, "/topic/**");

        serverBroadcast("/topic/dm/1", "{\"body\":\"hi hop, this is private\"}");

        assertEquals(List.of("hop-socket"), socketsThatReceivedSomething());
    }

    @Test
    void aClientSendToADmTopicReachesNobody() {
        connect("hop-socket");
        connect("outsider-socket");
        subscribe("hop-socket", "hop", "/topic/dm/1");

        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setSessionId("outsider-socket");
        send.setSessionAttributes(Map.of());
        send.setDestination("/topic/dm/1");
        clientInbound.send(MessageBuilder.createMessage(
                "{\"senderName\":\"Thomas Edison\",\"body\":\"FAKE: click this link\"}".getBytes(StandardCharsets.UTF_8),
                send.getMessageHeaders()));

        assertEquals(List.of(), socketsThatReceivedSomething());
    }

    /** The broker only delivers to sockets that sent CONNECT, as every STOMP client does first. */
    private void connect(String socketId) {
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId(socketId);
        connect.setSessionAttributes(Map.of());
        connect.setHeader(SimpMessageHeaderAccessor.HEART_BEAT_HEADER, new long[] {0, 0});
        clientInbound.send(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));
    }

    private void subscribe(String socketId, String uid, String destination) {
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId(socketId);
        subscribe.setSubscriptionId("sub-" + subscriptionCount++);
        subscribe.setDestination(destination);
        subscribe.setSessionAttributes(uid == null ? Map.of() : Map.of(ChatSocketIdentityInterceptor.UID_ATTRIBUTE, uid));
        clientInbound.send(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
    }

    /** What DirectMessageService's SimpMessagingTemplate does: a MESSAGE on the broker channel. */
    private void serverBroadcast(String destination, String json) {
        SimpMessageHeaderAccessor broadcast = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        broadcast.setDestination(destination);
        brokerChannel.send(MessageBuilder.createMessage(json.getBytes(StandardCharsets.UTF_8), broadcast.getMessageHeaders()));
    }

    private List<String> socketsThatReceivedSomething() {
        return delivered.stream()
                .filter(message -> SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE)
                .map(message -> SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))
                .toList();
    }
}
