package com.open.spring.mvc.calls;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/** The calls.* settings in application.properties; the safety rules' settings are in CallPolicySettings. */
@Component
@ConfigurationProperties(prefix = "calls")
@Data
public class CallsProperties {

    /** Off: every /api/calls route answers 404 calls_disabled and the call_room table isn't created. */
    private boolean enabled = false;

    /** The exact page origins that may call /api/calls (CallRequestGuard). */
    private List<String> allowedOrigins = new ArrayList<>();

    /** What a call can be for, by the names Pages uses; the first is used when a request names none. */
    private List<String> purposes = new ArrayList<>(List.of("meeting"));
}
