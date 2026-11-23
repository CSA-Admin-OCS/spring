package com.open.spring.mvc.person;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A mentor's own Capstone choices. The mentor is always the signed-in person, and the state rules
 * are on Person. Teachers and Admins decide requests in the Person admin page.
 */
@RestController
@RequestMapping("/api/person/capstones")
public class PersonCapstoneApiController {

    @Autowired
    private PersonDetailsService personDetailsService;

    @GetMapping
    public ResponseEntity<Map<String, Map<String, Object>>> getChoices(Authentication authentication) {
        return ResponseEntity.ok(personDetailsService.readCapstoneMentoring(mentorUid(authentication),
                PersonCapstoneApiController::visibleChoices));
    }

    @PutMapping("/choice")
    public ResponseEntity<Map<String, Map<String, Object>>> setChoice(@RequestBody Map<String, String> body,
                                                                      Authentication authentication) {
        String project = body.get("project");
        String status = String.valueOf(body.get("status"));
        return ResponseEntity.ok(personDetailsService.changeCapstoneMentoring(mentorUid(authentication), person -> {
            switch (status) {
                case Person.CAPSTONE_INTERESTED -> person.markCapstoneInterested(project);
                case Person.CAPSTONE_SKIPPED -> person.skipCapstone(project);
                case "none" -> person.clearCapstoneChoice(project);
                default -> throw new IllegalArgumentException("status must be interested, skipped, or none.");
            }
            return visibleChoices(person);
        }));
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Map<String, Object>>> request(@RequestBody Map<String, String> body,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(personDetailsService.changeCapstoneMentoring(mentorUid(authentication), person -> {
            person.requestCapstone(body.get("project"));
            return visibleChoices(person);
        }));
    }

    @DeleteMapping("/request")
    public ResponseEntity<Map<String, Map<String, Object>>> cancelRequest(@RequestBody Map<String, String> body,
                                                                          Authentication authentication) {
        return ResponseEntity.ok(personDetailsService.changeCapstoneMentoring(mentorUid(authentication), person -> {
            person.cancelCapstoneRequest(body.get("project"));
            return visibleChoices(person);
        }));
    }

    @DeleteMapping("/choices")
    public ResponseEntity<Map<String, Integer>> reset(Authentication authentication) {
        int cleared = personDetailsService.changeCapstoneMentoring(mentorUid(authentication),
                Person::resetCapstoneChoices);
        return ResponseEntity.ok(Map.of("cleared", cleared));
    }

    /** Public: approved mentors stay visible to every visitor, by name only. */
    @GetMapping("/mentors")
    public ResponseEntity<Map<String, List<String>>> approvedMentors() {
        return ResponseEntity.ok(personDetailsService.approvedCapstoneMentorNames());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleRefused(IllegalStateException refused) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", refused.getMessage()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, String>> handleOverlap(ConcurrencyFailureException overlap) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Another change was saving at the same time. Try again."));
    }

    private String mentorUid(Authentication authentication) {
        boolean mentor = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_MENTOR".equals(granted.getAuthority()));
        if (!mentor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only approved mentors can do this.");
        }
        return authentication.getName();
    }

    // decidedBy names a Teacher or Admin, so it stays out of the mentor's response.
    private static Map<String, Map<String, Object>> visibleChoices(Person person) {
        Map<String, Map<String, Object>> visible = new LinkedHashMap<>();
        if (person.getCapstoneMentoring() != null) {
            person.getCapstoneMentoring().forEach((project, entry) -> {
                Map<String, Object> shown = new LinkedHashMap<>(entry);
                shown.remove("decidedBy");
                visible.put(project, shown);
            });
        }
        return visible;
    }
}
