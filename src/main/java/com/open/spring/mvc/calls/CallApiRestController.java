package com.open.spring.mvc.calls;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * /api/calls: the call room of a group, DM conversation or course. Off while calls.enabled is false.
 * Every route checks the page origin (CallRequestGuard) and a signed-in person; CallRoomService
 * decides who may join. Refusals are {"reason": ...} (CallExceptionHandler).
 */
@RestController
@RequestMapping("/api/calls")
public class CallApiRestController {

    /** POST /api/calls body, for example {"contextType":"GROUP","contextKey":"12","purpose":"standup"}. */
    public record OpenRequest(String contextType, String contextKey, String purpose) {}

    /** A room as the call page sees it; its LiveKit name and who opened it stay in Spring. */
    public record CallView(Long id, String state, String title, String contextType, String contextKey, String purpose) {
        static CallView of(CallRoomEntity room) {
            return new CallView(room.getId(), room.getState().name(), room.getTitle(), room.getContextType().name(),
                    room.getContextKey(), room.getPurpose());
        }
    }

    private final CallsProperties properties;
    private final CallRequestGuard guard;
    private final CallRoomService rooms;
    private final PersonJpaRepository people;

    public CallApiRestController(CallsProperties properties, CallRequestGuard guard, CallRoomService rooms,
            PersonJpaRepository people) {
        this.properties = properties;
        this.guard = guard;
        this.rooms = rooms;
        this.people = people;
    }

    /**
     * POST /api/calls - the room of a group, DM or course, made on first use: 200, or 400
     * UNKNOWN_CONTEXT_TYPE / UNKNOWN_PURPOSE, 403 NOT_A_MEMBER, 404 UNKNOWN_CONTEXT.
     */
    @PostMapping
    public CallView open(@RequestBody OpenRequest body, Authentication authentication, HttpServletRequest request) {
        Person person = signedIn(authentication, request);
        CallContextType type = CallContextType.from(body.contextType());
        try {
            return CallView.of(rooms.openFor(person, type, body.contextKey(), body.purpose()));
        } catch (DataAccessException lostRace) {
            // Someone else opened this context's first room at the same moment and the unique key
            // refused this one (SQLite reports it as a JpaSystemException, MySQL as a
            // DataIntegrityViolationException). Theirs is saved, so open it; a real failure fails again.
            return CallView.of(rooms.openFor(person, type, body.contextKey(), body.purpose()));
        }
    }

    /** GET /api/calls/{id} - 200 for someone who may join the room, 403 NOT_A_MEMBER, 404 CALL_NOT_FOUND. */
    @GetMapping("/{id:\\d+}")
    public CallView get(@PathVariable long id, Authentication authentication, HttpServletRequest request) {
        Person person = signedIn(authentication, request);
        return CallView.of(rooms.find(person, id));
    }

    private Person signedIn(Authentication authentication, HttpServletRequest request) {
        if (!properties.isEnabled()) {
            throw new CallException(HttpStatus.NOT_FOUND, "calls_disabled");
        }
        guard.check(request);
        Person person = authentication == null ? null : people.findByUid(authentication.getName());
        if (person == null) {
            throw new CallException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return person;
    }
}
