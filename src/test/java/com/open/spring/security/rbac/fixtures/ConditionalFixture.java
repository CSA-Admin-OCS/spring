package com.open.spring.security.rbac.fixtures;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A controller that exists only under a profile. Never part of the application. */
@RestController
@Profile("never-active")
public class ConditionalFixture {

    @GetMapping("/api/cond")
    public String only() {
        return "";
    }
}
