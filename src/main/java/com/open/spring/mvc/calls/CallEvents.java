package com.open.spring.mvc.calls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.GroupChatRealtimeService;

/**
 * Tells the chats people already have open that a call is live: a CallEvent on the group's or DM's
 * topic, sent straight through SimpMessagingTemplate so the chat classes don't change. Chat pages
 * ignore a frame context they don't know. A course room sends nothing; course pages ask
 * GET /api/calls/live. A frame is a hint, so a broker failure is logged and the call goes on.
 */
@Component
public class CallEvents {

    private static final Logger logger = LoggerFactory.getLogger(CallEvents.class);

    private final SimpMessagingTemplate messaging;

    public CallEvents(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void live(CallRoomEntity room, int count) {
        send(room, CallEvent.of(room.getId(), "live", count));
    }

    public void ended(CallRoomEntity room) {
        send(room, CallEvent.of(room.getId(), "ended", 0));
    }

    private void send(CallRoomEntity room, CallEvent event) {
        String topic = switch (room.getContextType()) {
            case GROUP -> GroupChatRealtimeService.GROUP_TOPIC_PREFIX + room.getContextKey();
            case DM -> DirectMessageService.DM_TOPIC_PREFIX + room.getContextKey();
            case COURSE -> null;
        };
        if (topic == null) {
            return;
        }
        try {
            messaging.convertAndSend(topic, event);
        } catch (MessagingException e) {
            logger.warn("Could not tell {} about call {}: {}", topic, room.getId(), e.getMessage());
        }
    }
}
