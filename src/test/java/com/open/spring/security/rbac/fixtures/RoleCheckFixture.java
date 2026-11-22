package com.open.spring.security.rbac.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller that decides by itself who may do something. The role words here are what the inventory must find:
 * ROLE_ADMIN and hasRole( in the code below, and nothing from a comment such as ROLE_TEACHER or from an annotation.
 */
@RestController
public class RoleCheckFixture {

    interface Caller {
        boolean hasRole(String role);
    }

    @GetMapping("/api/rc/check")
    public String check(Caller caller) {
        // a comment that says ROLE_TEACHER and hasAuthority( is not code
        /* neither is this one: getAuthorities( */
        if (caller.hasRole("ROLE_ADMIN")) {
            return "admin";
        }
        return "other";
    }

    @PreAuthorize("hasAuthority('ROLE_STRIPPED')")
    @GetMapping("/api/rc/annotated")
    public String annotated() {
        return "";
    }
}
