package com.open.spring.mvc.calls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;

/**
 * The calls happening now that a person may join, for course pages (GET /api/calls/live). A
 * supervisor may join any room, so they see every live call, with the one-adult-one-minor calls
 * marked (calls.policy.one-adult-one-minor=ALLOW_AND_LIST lists them this way).
 */
@Service
public class CallLiveService {

    /** One live call: no names of who is in it, only how many. */
    public record LiveCall(Long id, String title, String contextType, int count, boolean oneOnOne) {}

    private final CallRoomJpaRepository rooms;
    private final CallRoomService access;
    private final CallPresence presence;
    private final GroupsJpaRepository groups;
    private final CallPolicySettings policy;

    public CallLiveService(CallRoomJpaRepository rooms, CallRoomService access, CallPresence presence,
            GroupsJpaRepository groups, CallPolicySettings policy) {
        this.rooms = rooms;
        this.access = access;
        this.presence = presence;
        this.groups = groups;
        this.policy = policy;
    }

    /** Live calls with someone in them that this person may join; with a course, only that course's. */
    @Transactional(readOnly = true)
    public List<LiveCall> liveFor(Person person, String course) {
        String courseName = course == null || course.isBlank() ? null : course.trim().toUpperCase(Locale.ROOT);
        List<LiveCall> live = new ArrayList<>();
        for (CallRoomEntity room : rooms.findByState(CallState.LIVE)) {
            Collection<CallParticipant> present = presence.present(room.getRoomName());
            if (present.isEmpty() || !inCourse(room, courseName) || !access.canJoin(person, room)) {
                continue;
            }
            live.add(new LiveCall(room.getId(), room.getTitle(), room.getContextType().name(), present.size(),
                    CallPolicy.isOneAdultWithOneMinor(policy, present)));
        }
        return live;
    }

    // A course's calls are its own room and its teams' group rooms.
    private boolean inCourse(CallRoomEntity room, String courseName) {
        if (courseName == null) {
            return true;
        }
        return switch (room.getContextType()) {
            case COURSE -> room.getContextKey().equals(courseName);
            case GROUP -> groups.findById(Long.parseLong(room.getContextKey()))
                    .map(group -> group.getCourse() != null && group.getCourse().trim().equalsIgnoreCase(courseName))
                    .orElse(false);
            case DM -> false;
        };
    }
}
