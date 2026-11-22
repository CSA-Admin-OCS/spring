package com.open.spring.mvc.calls;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * A room as the call page sees it, with how many people are connected; its LiveKit name and
     * who opened it stay in Spring.
     */
    public record CallView(Long id, String state, String title, String contextType, String contextKey, String purpose,
            int participantCount) {
    }

    private final CallsProperties properties;
    private final CallRequestGuard guard;
    private final CallRoomService rooms;
    private final CallTokenService tokens;
    private final CallModerationService moderation;
    private final CallPresence presence;
    private final PersonJpaRepository people;

    public CallApiRestController(CallsProperties properties, CallRequestGuard guard, CallRoomService rooms,
            CallTokenService tokens, CallModerationService moderation, CallPresence presence, PersonJpaRepository people) {
        this.properties = properties;
        this.guard = guard;
        this.rooms = rooms;
        this.tokens = tokens;
        this.moderation = moderation;
        this.presence = presence;
        this.people = people;
    }

    private CallView view(CallRoomEntity room) {
        return new CallView(room.getId(), room.getState().name(), room.getTitle(), room.getContextType().name(),
                room.getContextKey(), room.getPurpose(), presence.present(room.getRoomName()).size());
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
            return view(rooms.openFor(person, type, body.contextKey(), body.purpose()));
        } catch (DataAccessException lostRace) {
            // Someone else opened this context's first room at the same moment and the unique key
            // refused this one (SQLite reports it as a JpaSystemException, MySQL as a
            // DataIntegrityViolationException). Theirs is saved, so open it; a real failure fails again.
            return view(rooms.openFor(person, type, body.contextKey(), body.purpose()));
        }
    }

    /** GET /api/calls/{id} - 200 for someone who may join the room, 403 NOT_A_MEMBER, 404 CALL_NOT_FOUND. */
    @GetMapping("/{id:\\d+}")
    public CallView get(@PathVariable long id, Authentication authentication, HttpServletRequest request) {
        Person person = signedIn(authentication, request);
        return view(rooms.find(person, id));
    }

    /** POST /api/calls/{id}/participants/{identity}/pause - a moderator cuts someone off: 204, or 404 PARTICIPANT_NOT_FOUND. */
    @PostMapping("/{id:\\d+}/participants/{identity:[A-Za-z0-9_-]{1,64}}/pause")
    public ResponseEntity<Void> pause(@PathVariable long id, @PathVariable String identity,
            Authentication authentication, HttpServletRequest request) {
        moderation.pause(signedIn(authentication, request), id, identity);
        return ResponseEntity.noContent().build();
    }

    /** POST .../resume - 204, or 409 WAITING_FOR_SUPERVISOR while the safety rules keep them paused. */
    @PostMapping("/{id:\\d+}/participants/{identity:[A-Za-z0-9_-]{1,64}}/resume")
    public ResponseEntity<Void> resume(@PathVariable long id, @PathVariable String identity,
            Authentication authentication, HttpServletRequest request) {
        moderation.resume(signedIn(authentication, request), id, identity);
        return ResponseEntity.noContent().build();
    }

    /** POST .../remove - 204; the person gets no new token for this call. */
    @PostMapping("/{id:\\d+}/participants/{identity:[A-Za-z0-9_-]{1,64}}/remove")
    public ResponseEntity<Void> remove(@PathVariable long id, @PathVariable String identity,
            Authentication authentication, HttpServletRequest request) {
        moderation.remove(signedIn(authentication, request), id, identity);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/calls/{id}/end - a moderator ends the call for everyone: 204. */
    @PostMapping("/{id:\\d+}/end")
    public ResponseEntity<Void> end(@PathVariable long id, Authentication authentication, HttpServletRequest request) {
        moderation.end(signedIn(authentication, request), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/calls/{id}/token - a LiveKit join token: 200 {url, token, expiresIn, canModerate}, 403
     * WAITING_FOR_SUPERVISOR / WAITING_FOR_SECOND_ADULT / REMOVED / NOT_A_MEMBER, or 503 MEDIA_UNAVAILABLE.
     */
    @PostMapping("/{id:\\d+}/token")
    public CallTokenService.JoinToken token(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        return tokens.tokenFor(signedIn(authentication, request), id);
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
