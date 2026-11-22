package com.open.spring.security.rbac.fixtures;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** A page controller, and the path the API chain takes that is not under /api. Never part of the application. */
@Controller
public class ViewFixture {

    @GetMapping("/mvc/page")
    public String page() {
        return "page";
    }

    @GetMapping("/mvc/data")
    @ResponseBody
    public String data() {
        return "data";
    }

    @PostMapping("/authenticate")
    @ResponseBody
    public String authenticate() {
        return "token";
    }

    @GetMapping("/{anything}/x")
    public String firstSegmentIsAVariable() {
        return "x";
    }
}
