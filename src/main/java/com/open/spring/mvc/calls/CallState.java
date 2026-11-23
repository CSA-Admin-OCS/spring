package com.open.spring.mvc.calls;

/** A room is IDLE until someone is connected to it and LIVE while anyone is (set from LiveKit's webhooks). */
public enum CallState {
    IDLE, LIVE
}
