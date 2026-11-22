package com.open.spring.mvc.calls;

import java.util.Set;

/** Someone in a call, as the safety rules see them: their LiveKit identity, uid and role names. */
public record CallParticipant(String identity, String uid, Set<String> roles) {

    public CallParticipant {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
