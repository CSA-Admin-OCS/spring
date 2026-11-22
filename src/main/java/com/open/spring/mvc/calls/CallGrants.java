package com.open.spring.mvc.calls;

import java.util.List;

/**
 * What a participant may do in LiveKit. Every value is always written into the token, because
 * LiveKit allows anything a token leaves out.
 */
public record CallGrants(boolean canPublish, boolean canSubscribe, boolean canPublishData, List<String> sources) {

    /** Send camera, microphone and screen, hear and see everyone, and send data. */
    public static final CallGrants FULL = new CallGrants(true, true, true, List.of("camera", "microphone", "screen_share"));

    /** Connected but cut off: nothing sent, nothing received. */
    public static final CallGrants PAUSED = new CallGrants(false, false, false, List.of());

    public CallGrants {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
