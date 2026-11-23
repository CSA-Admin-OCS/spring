package com.open.spring.mvc.directmessages;

import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import com.open.spring.mvc.groups.ChatSocketIdentityInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * The chat socket's half of the rule in {@link DirectMessageService#isParticipant}: only a
 * conversation's participants see its messages. Every frame a client sends on {@code /ws-chat}
 * passes through here before the broker:
 * <ul>
 * <li>SUBSCRIBE to {@code /topic/dm/{id}} only for that conversation's participants, using the
 * uid {@link ChatSocketIdentityInterceptor} read from the login cookie.</li>
 * <li>No wildcard SUBSCRIBE on any topic. The broker matches {@code *}, {@code **}, {@code ?}
 * and {@code {name}} as patterns, so {@code /topic/**} would get every DM with no id at all.
 * No client uses one.</li>
 * <li>No client SEND to a DM topic. The broker relays a client's SEND to every subscriber, so it
 * would show up in someone's thread as a real message. Only the server posts there.</li>
 * <li>No SUBSCRIBE or SEND to a user destination ({@code /user/...}). Nothing on this socket uses
 * them, and Spring rewrites a {@code /user/X} subscription to X plus "-user" plus the session id
 * after this check. A SockJS client picks its own session id, so the two halves together can make
 * a wildcard this check never sees.</li>
 * </ul>
 * Group topics, {@code /app/groups.chat} and every other frame pass untouched.
 */
@Slf4j
@Component
public class DirectMessageTopicGuard implements ChannelInterceptor {

    /** The broker's subscription registry uses this same matcher, so it decides what is a wildcard. */
    private static final PathMatcher BROKER_MATCHER = new AntPathMatcher();
    /** Spring's default user destination prefix; WebSocketBrokerConfig doesn't change it. */
    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String NO_USER_DESTINATIONS = "user destinations are not used on this socket";
    private static final int LOGGED_DESTINATION_LENGTH = 200;

    private final DirectMessageConversationJpaRepository conversationRepository;

    public DirectMessageTopicGuard(DirectMessageConversationJpaRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(headers);
        String destination = SimpMessageHeaderAccessor.getDestination(headers);

        String refusal = null;
        if (type == SimpMessageType.SUBSCRIBE) {
            refusal = whySubscribeIsRefused(destination, SimpMessageHeaderAccessor.getSessionAttributes(headers));
        } else if (type == SimpMessageType.MESSAGE) {
            refusal = whySendIsRefused(destination);
        }
        if (refusal == null) {
            return message;
        }

        // Dropped rather than answered with an ERROR frame: STOMP closes the whole socket after
        // an ERROR, and the page would lose its group chat too.
        log.warn("Chat socket {} refused {} to {}: {}", SimpMessageHeaderAccessor.getSessionId(headers), type,
                forLog(destination), refusal);
        return null;
    }

    /** Why this subscription is refused, or null to let it through. */
    private String whySubscribeIsRefused(String destination, Map<String, Object> sessionAttributes) {
        if (destination == null) {
            return null; // the broker ignores a subscription with no destination
        }
        if (BROKER_MATCHER.isPattern(destination)) {
            return "wildcard subscriptions are not allowed";
        }
        if (isUserDestination(destination)) {
            return NO_USER_DESTINATIONS;
        }
        if (!isDirectMessageTopic(destination)) {
            return null;
        }
        String uid = ChatSocketIdentityInterceptor.uidOf(sessionAttributes);
        if (uid == null) {
            return "not signed in";
        }
        Long conversationId = conversationIdOf(destination);
        if (conversationId == null || !conversationRepository.existsByIdAndParticipantsUid(conversationId, uid)) {
            return uid + " is not a participant";
        }
        return null;
    }

    /** Why a client's SEND is refused, or null to let it through. */
    private static String whySendIsRefused(String destination) {
        if (isDirectMessageTopic(destination)) {
            return "only the server sends to direct message topics";
        }
        if (isUserDestination(destination)) {
            return NO_USER_DESTINATIONS;
        }
        return null;
    }

    private static boolean isDirectMessageTopic(String destination) {
        return destination != null && destination.startsWith(DirectMessageService.DM_TOPIC_PREFIX);
    }

    private static boolean isUserDestination(String destination) {
        return destination != null && destination.startsWith(USER_DESTINATION_PREFIX);
    }

    /**
     * The id in {@code /topic/dm/{id}}, accepted only in the exact form the server broadcasts to
     * ({@code DM_TOPIC_PREFIX + id}), so the id checked is the topic the broker delivers.
     */
    private static Long conversationIdOf(String destination) {
        String id = destination.substring(DirectMessageService.DM_TOPIC_PREFIX.length());
        try {
            long value = Long.parseLong(id);
            return value > 0 && Long.toString(value).equals(id) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** A client picks the destination, so keep it to one short log line. */
    private static String forLog(String destination) {
        String oneLine = destination.replaceAll("\\p{Cntrl}", "?");
        return oneLine.length() <= LOGGED_DESTINATION_LENGTH ? oneLine : oneLine.substring(0, LOGGED_DESTINATION_LENGTH) + "...";
    }
}
