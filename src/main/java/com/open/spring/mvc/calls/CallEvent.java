package com.open.spring.mvc.calls;

/**
 * The frame a group's or DM's chat topic gets when a call there goes live, changes size or ends:
 * {"context":"callServer","callId":5,"state":"live","count":2}. It carries no names and no links,
 * since a client can fake a frame on a group topic; pages ask GET /api/calls/{id} before showing it.
 */
public record CallEvent(String context, Long callId, String state, int count) {

    static final String CONTEXT = "callServer";

    static CallEvent of(Long callId, String state, int count) {
        return new CallEvent(CONTEXT, callId, state, count);
    }
}
