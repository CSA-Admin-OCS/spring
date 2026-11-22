package com.open.spring.mvc.calls;

import org.springframework.http.HttpStatus;

/** What a call room belongs to: each group, DM conversation and course has one room. */
public enum CallContextType {
    GROUP, DM, COURSE;

    /** The type a request names exactly ("GROUP", "DM" or "COURSE"); anything else is 400 UNKNOWN_CONTEXT_TYPE. */
    public static CallContextType from(String name) {
        for (CallContextType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        throw new CallException(HttpStatus.BAD_REQUEST, "UNKNOWN_CONTEXT_TYPE");
    }
}
