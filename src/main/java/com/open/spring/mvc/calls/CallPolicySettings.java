package com.open.spring.mvc.calls;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * The calls.policy.* settings: what each role may do in a call. Role names live only in
 * application.properties, so the calls code holds no role of its own.
 */
@Component
@ConfigurationProperties(prefix = "calls.policy")
@Data
public class CallPolicySettings {

    /** Roles that may join any room to watch over it. */
    private List<String> supervisorRoles = new ArrayList<>();
}
