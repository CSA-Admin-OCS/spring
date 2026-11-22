package com.open.spring.security.rbac.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** A controller with a known answer for each way a mapping can be written. Never part of the application. */
@RestController
@RequestMapping("/api/a")
@PreAuthorize("isAuthenticated()")
public class ApiFixture {

    @GetMapping("/x")
    public String one() {
        return "";
    }

    @PostMapping({"/p", "/q"})
    public String twoPaths() {
        return "";
    }

    @RequestMapping("/any")
    public String noMethodNamed() {
        return "";
    }

    @RequestMapping(value = "/two", method = {RequestMethod.GET, RequestMethod.DELETE})
    public String twoMethods() {
        return "";
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/u/{id}")
    public String guarded(@PathVariable long id) {
        return "";
    }

    public String notAHandler() {
        return "";
    }
}
