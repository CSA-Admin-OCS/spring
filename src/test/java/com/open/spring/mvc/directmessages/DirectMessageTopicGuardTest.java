package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import com.open.spring.mvc.groups.ChatSocketIdentityInterceptor;

/**
 * What a client may do with /topic/dm/{id} on the chat socket: only the conversation's
 * participants subscribe, nobody subscribes with a wildcard, and only the server sends.
 * Everything else on the socket (group chat, connecting) passes through untouched.
 */
class DirectMessageTopicGuardTest {

    private static final Map<String, Object> HOP = Map.of(ChatSocketIdentityInterceptor.UID_ATTRIBUTE, "hop");
    private static final Map<String, Object> NIKO = Map.of(ChatSocketIdentityInterceptor.UID_ATTRIBUTE, "niko");
    private static final Map<String, Object> SIGNED_OUT = Map.of();

    private final MessageChannel channel = new ExecutorSubscribableChannel();
    private DirectMessageConversationJpaRepository conversations;
    private DirectMessageTopicGuard guard;

    @BeforeEach
    void setUp() {
        conversations = mock(DirectMessageConversationJpaRepository.class);
        when(conversations.existsByIdAndParticipantsUid(1L, "hop")).thenReturn(true);
        guard = new DirectMessageTopicGuard(conversations);
    }

    @Test
    void participantCanFollowTheirConversation() {
        Message<?> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/dm/1", HOP);

        assertSame(subscribe, guard.preSend(subscribe, channel));
    }

    @Test
    void signedInOutsiderIsRefused() {
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/topic/dm/1", NIKO), channel));
    }

    @Test
    void signedOutSocketIsRefused() {
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/topic/dm/1", SIGNED_OUT), channel));
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/topic/dm/1", null), channel));
    }

    /** The broker treats these as patterns, so one subscription would match many topics. */
    @ParameterizedTest
    @ValueSource(strings = {"/topic/dm/*", "/topic/dm/**", "/topic/**", "/topic/*/1", "/topic/dm/{id}", "/topic/dm/?",
            "/topic/group/*"})
    void wildcardSubscriptionsAreRefused(String destination) {
        when(conversations.existsByIdAndParticipantsUid(anyLong(), eq("hop"))).thenReturn(true);

        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, destination, HOP), channel));
    }

    /** The server only broadcasts to /topic/dm/ + the id as a plain number, so nothing else is a DM topic to allow. */
    @ParameterizedTest
    @ValueSource(strings = {"/topic/dm/", "/topic/dm/abc", "/topic/dm/1/extra", "/topic/dm/1/", "/topic/dm/01",
            "/topic/dm/-1", "/topic/dm/0", "/topic/dm/99999999999999999999"})
    void oddConversationIdsAreRefused(String destination) {
        when(conversations.existsByIdAndParticipantsUid(anyLong(), eq("hop"))).thenReturn(true);

        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, destination, HOP), channel));
    }

    /** Nothing on this socket uses user destinations, and Spring rewrites them out of the guard's sight. */
    @Test
    void userDestinationsAreRefused() {
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/user/topic/dm/{x:1|", SIGNED_OUT), channel));
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/user/topic/dm/1", HOP), channel));
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/errors", HOP), channel));
        assertNull(guard.preSend(frame(StompCommand.SEND, "/user/hop/queue/messages", SIGNED_OUT), channel));
    }

    @Test
    void clientCannotSendToADmTopic() {
        assertNull(guard.preSend(frame(StompCommand.SEND, "/topic/dm/1", HOP), channel));
        assertNull(guard.preSend(frame(StompCommand.SEND, "/topic/dm/1", SIGNED_OUT), channel));
    }

    @Test
    void groupChatAndOtherFramesPassUntouched() {
        Message<?>[] untouched = {
            frame(StompCommand.SUBSCRIBE, "/topic/group/7", SIGNED_OUT),
            frame(StompCommand.SUBSCRIBE, null, SIGNED_OUT),
            frame(StompCommand.SEND, "/app/groups.chat", SIGNED_OUT),
            frame(StompCommand.CONNECT, null, SIGNED_OUT),
            frame(StompCommand.UNSUBSCRIBE, null, SIGNED_OUT),
            frame(StompCommand.DISCONNECT, null, SIGNED_OUT),
        };
        for (Message<?> message : untouched) {
            assertSame(message, guard.preSend(message, channel), String.valueOf(message.getHeaders()));
        }
    }

    private static Message<byte[]> frame(StompCommand command, String destination, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("socket-1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.UNSUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        if (sessionAttributes != null) {
            accessor.setSessionAttributes(sessionAttributes);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
