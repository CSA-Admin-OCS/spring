package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/** "A call is live" frames on the chat topics people already follow. */
class CallEventsTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CallEvents events = new CallEvents(messaging);

    private static CallRoomEntity room(CallContextType type, String key, long id) {
        CallRoomEntity room = CallRoomEntity.forContext(type, key, "ada");
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }

    @Test
    void groupRoomFrameGoesToTheGroupTopic() {
        events.live(room(CallContextType.GROUP, "12", 5), 2);

        verify(messaging).convertAndSend("/topic/group/12", new CallEvent("callServer", 5L, "live", 2));
    }

    @Test
    void dmRoomFrameGoesToTheDmTopic() {
        events.live(room(CallContextType.DM, "34", 6), 1);

        verify(messaging).convertAndSend("/topic/dm/34", new CallEvent("callServer", 6L, "live", 1));
    }

    @Test
    void anEndedCallSaysSo() {
        events.ended(room(CallContextType.GROUP, "12", 5));

        verify(messaging).convertAndSend("/topic/group/12", new CallEvent("callServer", 5L, "ended", 0));
    }

    @Test
    void courseRoomSendsNoFrame() {
        events.live(room(CallContextType.COURSE, "CSA", 7), 3);

        verifyNoInteractions(messaging);
    }

    @Test
    void frameCarriesNoNamesOrLinks() throws Exception {
        Set<String> keys = new HashSet<>();
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new CallEvent("callServer", 5L, "live", 2)))
                .fieldNames().forEachRemaining(keys::add);

        assertEquals(Set.of("context", "callId", "state", "count"), keys);
    }

    @Test
    void aBrokerFailureIsNotTheCallersProblem() {
        doThrow(new MessagingException("broker down")).when(messaging).convertAndSend(anyString(), any(Object.class));

        events.live(room(CallContextType.GROUP, "12", 5), 2);
    }
}
