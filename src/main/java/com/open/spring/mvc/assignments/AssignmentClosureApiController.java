package com.open.spring.mvc.assignments;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Closes an assignment (see {@link AssignmentClosureService}). */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentClosureApiController {

    public record ClosureResponse(GradingOutcome outcome, AssignmentClosureService.Summary summary) {
    }

    private final AssignmentClosureService closureService;
    private final PersonJpaRepository personRepo;

    public AssignmentClosureApiController(AssignmentClosureService closureService, PersonJpaRepository personRepo) {
        this.closureService = closureService;
        this.personRepo = personRepo;
    }

    @PostMapping("/{assignmentId}/close")
    public ResponseEntity<?> close(@PathVariable Long assignmentId, @AuthenticationPrincipal UserDetails userDetails) {
        Person teacher = userDetails == null ? null : personRepo.findByUid(userDetails.getUsername());
        if (teacher == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        AssignmentClosureService.Result result = closureService.close(assignmentId, teacher);
        return ResponseEntity.status(GradingResponse.statusOf(result.outcome()))
                .body(new ClosureResponse(result.outcome(), result.summary()));
    }
}
