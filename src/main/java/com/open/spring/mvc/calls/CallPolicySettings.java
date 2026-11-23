package com.open.spring.mvc.calls;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * The calls.policy.* settings: what each role may do in a call. Role names live only in
 * application.properties, so the calls code holds no role of its own. CallPolicy applies them.
 */
@Component
@ConfigurationProperties(prefix = "calls.policy")
@Data
public class CallPolicySettings {

    /** Roles that may join any room to watch over it. */
    private List<String> supervisorRoles = new ArrayList<>();

    /** Roles that may be in a call with minors only while a supervisor is connected. */
    private List<String> supervisedRoles = new ArrayList<>();

    /** Roles counted as minors, since OCS keeps no ages. */
    private List<String> minorRoles = new ArrayList<>();

    /** Roles that may moderate a call. */
    private List<String> moderatorRoles = new ArrayList<>();

    /** Seconds a supervised person keeps their video after the last supervisor leaves; 0 pauses them at once. */
    private int supervisorGraceSeconds = 0;

    /** What happens when a call would be one adult alone with one minor. */
    private OneAdultOneMinor oneAdultOneMinor = OneAdultOneMinor.ALLOW_AND_LIST;

    public enum OneAdultOneMinor {
        /** Allowed, and the call is marked so other supervisors can see it. */
        ALLOW_AND_LIST,
        /** The person who would make it a one-on-one waits until someone else is there. */
        REQUIRE_SECOND_ADULT
    }
}
