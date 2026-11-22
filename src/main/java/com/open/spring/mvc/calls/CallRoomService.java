package com.open.spring.mvc.calls;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.directmessages.DirectMessageConversationJpaRepository;
import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;

/**
 * Who may be in a call room, and the one room of each group, DM conversation and course. Membership
 * is read from the group, the DM or the course's class group, so any rule those already have (who
 * may join a group, say) applies to its calls with no calls code. Supervisors, the roles in
 * calls.policy.supervisor-roles, may join any room.
 *
 * The methods are transactional because open-in-view is off and a DM's participants load lazily.
 */
@Service
public class CallRoomService {

    // Group and DM ids as Spring writes them: no sign, no leading zero, small enough for a long.
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

    private final CallRoomJpaRepository rooms;
    private final GroupsJpaRepository groups;
    private final DirectMessageConversationJpaRepository conversations;
    private final DirectMessageService directMessages;
    private final CourseGroupProperties courses;
    private final CallsProperties properties;
    private final CallPolicySettings policy;

    public CallRoomService(CallRoomJpaRepository rooms, GroupsJpaRepository groups,
            DirectMessageConversationJpaRepository conversations, DirectMessageService directMessages,
            CourseGroupProperties courses, CallsProperties properties, CallPolicySettings policy) {
        this.rooms = rooms;
        this.groups = groups;
        this.conversations = conversations;
        this.directMessages = directMessages;
        this.courses = courses;
        this.properties = properties;
        this.policy = policy;
    }

    /** A group's or course's room is named after it; a DM's page names the conversation itself. */
    private record Context(String key, String title) {}

    /** Whether this person may be in this room. */
    @Transactional(readOnly = true)
    public boolean canJoin(Person person, CallRoomEntity room) {
        if (isSupervisor(person)) {
            return true;
        }
        return switch (room.getContextType()) {
            case GROUP -> inGroup(person, Long.parseLong(room.getContextKey()));
            case DM -> conversations.findById(Long.parseLong(room.getContextKey()))
                    .map(conversation -> directMessages.isParticipant(conversation, person))
                    .orElse(false);
            case COURSE -> groups.findByName(room.getContextKey())
                    .map(classGroup -> inGroup(person, classGroup.getId()))
                    .orElse(false);
        };
    }

    /**
     * The room of a group, DM or course, made the first time anyone opens it. The purpose is chosen
     * when a room opens: opening an idle room again sets it, and a live call keeps the one it began with.
     */
    @Transactional
    public CallRoomEntity openFor(Person person, CallContextType type, String key, String purpose) {
        String chosenPurpose = choosePurpose(purpose);
        Context context = resolve(type, key);
        CallRoomEntity room = rooms.findByContextTypeAndContextKey(type, context.key())
                .orElseGet(() -> CallRoomEntity.forContext(type, context.key(), person.getUid()));
        if (!canJoin(person, room)) {
            throw new CallException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER");
        }
        if (room.getState() != CallState.LIVE) {
            room.setPurpose(chosenPurpose);
        }
        room.setTitle(context.title());
        return rooms.save(room);
    }

    /** A room by its id, for someone who may join it. */
    @Transactional(readOnly = true)
    public CallRoomEntity find(Person person, long id) {
        CallRoomEntity room = rooms.findById(id)
                .orElseThrow(() -> new CallException(HttpStatus.NOT_FOUND, "CALL_NOT_FOUND"));
        if (!canJoin(person, room)) {
            throw new CallException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER");
        }
        return room;
    }

    private boolean isSupervisor(Person person) {
        return policy.getSupervisorRoles().stream().anyMatch(person::hasRole);
    }

    private boolean inGroup(Person person, long groupId) {
        return groups.findGroupsByPersonId(person.getId()).stream()
                .anyMatch(group -> Long.valueOf(groupId).equals(group.getId()));
    }

    // No purpose means the first one listed in calls.purposes.
    private String choosePurpose(String requested) {
        List<String> purposes = properties.getPurposes();
        if (requested == null || requested.isEmpty()) {
            if (purposes.isEmpty()) {
                throw new CallException(HttpStatus.BAD_REQUEST, "UNKNOWN_PURPOSE");
            }
            return purposes.get(0);
        }
        if (!purposes.contains(requested)) {
            throw new CallException(HttpStatus.BAD_REQUEST, "UNKNOWN_PURPOSE");
        }
        return requested;
    }

    // The context must exist: a group or DM by its id, or a course listed in courses.class-groups.
    private Context resolve(CallContextType type, String key) {
        String value = key == null ? "" : key;
        Optional<Context> context = switch (type) {
            case GROUP -> idIn(value).flatMap(groups::findById)
                    .map(group -> new Context(String.valueOf(group.getId()), group.getName()));
            case DM -> idIn(value).flatMap(conversations::findById)
                    .map(conversation -> new Context(String.valueOf(conversation.getId()), null));
            case COURSE -> courses.getGroupNames().stream()
                    .filter(name -> name.equals(value.toUpperCase(Locale.ROOT)))
                    .findFirst()
                    .map(name -> new Context(name, name));
        };
        return context.orElseThrow(() -> new CallException(HttpStatus.NOT_FOUND, "UNKNOWN_CONTEXT"));
    }

    private static Optional<Long> idIn(String value) {
        return ID.matcher(value).matches() ? Optional.of(Long.parseLong(value)) : Optional.empty();
    }
}
