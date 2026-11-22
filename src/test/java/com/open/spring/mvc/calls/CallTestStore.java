package com.open.spring.mvc.calls;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.directmessages.DirectMessageConversation;
import com.open.spring.mvc.directmessages.DirectMessageConversationJpaRepository;
import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import jakarta.persistence.PersistenceException;

/**
 * The call_room table and the people, groups and DM conversations it points at, as in-memory maps
 * behind Mockito repositories (as UiBuilderTestStore does), so the real CallRoomService and
 * controller run without a database. CallRoomPersistenceTest runs the entity on real SQLite.
 */
final class CallTestStore {

    final CallRoomJpaRepository rooms = mock(CallRoomJpaRepository.class);
    final GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
    final DirectMessageConversationJpaRepository conversations = mock(DirectMessageConversationJpaRepository.class);
    final DirectMessageService directMessages = mock(DirectMessageService.class);
    final PersonJpaRepository people = mock(PersonJpaRepository.class);
    final CourseGroupProperties courses = new CourseGroupProperties();
    final CallsProperties properties = new CallsProperties();
    final CallPolicySettings policy = new CallPolicySettings();
    final CallRoomService service;

    private final Map<Long, CallRoomEntity> roomRows = new LinkedHashMap<>();
    private final Map<Long, Groups> groupRows = new LinkedHashMap<>();
    private final Map<Long, List<Long>> groupMemberIds = new HashMap<>();
    private final Map<Long, DirectMessageConversation> conversationRows = new HashMap<>();
    private final Map<String, Person> peopleByUid = new HashMap<>();
    private long nextRoomId = 1;
    private CallRoomEntity savingElsewhere;
    private boolean tableMissing;

    CallTestStore() {
        courses.setClassGroups(List.of("CSA", "CSP"));
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of("https://pages.opencodingsociety.com", "http://localhost:4500"));
        properties.setPurposes(List.of("meeting", "standup"));
        policy.setSupervisorRoles(List.of("ROLE_TEACHER", "ROLE_ADMIN"));

        when(people.findByUid(anyString())).thenAnswer(call -> peopleByUid.get(call.<String>getArgument(0)));
        when(groups.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(groupRows.get(call.<Long>getArgument(0))));
        when(groups.findByName(anyString())).thenAnswer(call -> groupRows.values().stream()
                .filter(group -> group.getName().equals(call.getArgument(0)))
                .findFirst());
        when(groups.findGroupsByPersonId(anyLong())).thenAnswer(call -> groupRows.values().stream()
                .filter(group -> groupMemberIds.get(group.getId()).contains(call.<Long>getArgument(0)))
                .toList());
        when(conversations.findById(anyLong()))
                .thenAnswer(call -> Optional.ofNullable(conversationRows.get(call.<Long>getArgument(0))));
        // DirectMessageService's own rule: a participant is in the conversation's list, compared by id.
        when(directMessages.isParticipant(any(), any())).thenAnswer(call -> {
            DirectMessageConversation conversation = call.getArgument(0);
            Person person = call.getArgument(1);
            return conversation.getParticipants().stream().anyMatch(p -> p.getId().equals(person.getId()));
        });
        when(rooms.findById(anyLong())).thenAnswer(call -> {
            failIfTableIsMissing();
            return Optional.ofNullable(roomRows.get(call.<Long>getArgument(0)));
        });
        when(rooms.findByContextTypeAndContextKey(any(), anyString())).thenAnswer(call -> {
            failIfTableIsMissing();
            return lookUp(call.getArgument(0), call.getArgument(1));
        });
        when(rooms.save(any(CallRoomEntity.class))).thenAnswer(call -> save(call.getArgument(0)));

        service = new CallRoomService(rooms, groups, conversations, directMessages, courses, properties, policy);
    }

    // ---- people, groups and conversations ----

    Person person(long id, String uid, String... roles) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        person.setRoles(new ArrayList<>(Arrays.asList(roles)));
        peopleByUid.put(uid, person);
        return person;
    }

    Groups group(long id, String name, Person... members) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        groupRows.put(id, group);
        groupMemberIds.put(id, Arrays.stream(members).map(Person::getId).toList());
        return group;
    }

    DirectMessageConversation conversation(long id, Person... participants) {
        DirectMessageConversation conversation = new DirectMessageConversation(new ArrayList<>(List.of(participants)));
        ReflectionTestUtils.setField(conversation, "id", id);
        conversationRows.put(id, conversation);
        return conversation;
    }

    /** The login a JWT cookie gives this person: their uid and their roles. */
    static UsernamePasswordAuthenticationToken login(Person person) {
        return new UsernamePasswordAuthenticationToken(person.getUid(), null,
                person.getRoles().stream().map(SimpleGrantedAuthority::new).toList());
    }

    // ---- the call_room table ----

    int roomCount() {
        return roomRows.size();
    }

    /** As if CallRoomTableInitializer couldn't create call_room: every read of it fails. */
    void tableIsMissing() {
        tableMissing = true;
    }

    private void failIfTableIsMissing() {
        if (tableMissing) {
            throw new InvalidDataAccessResourceUsageException(
                    "[SQLITE_ERROR] SQL error or missing database (no such table: call_room)");
        }
    }

    /**
     * Another request opens this context's first room at the same moment: the next lookup misses it,
     * and it is saved just after, so this request's insert hits the unique (context_type, context_key) key.
     */
    CallRoomEntity anotherRequestOpensFirst(CallContextType type, String key) {
        savingElsewhere = CallRoomEntity.forContext(type, key, "racer");
        savingElsewhere.setPurpose("meeting");
        return savingElsewhere;
    }

    private Optional<CallRoomEntity> lookUp(CallContextType type, String key) {
        Optional<CallRoomEntity> found = rowFor(type, key);
        if (savingElsewhere != null && savingElsewhere.getContextType() == type && savingElsewhere.getContextKey().equals(key)) {
            CallRoomEntity winner = savingElsewhere;
            savingElsewhere = null;
            winner.setId(nextRoomId++);
            roomRows.put(winner.getId(), winner);
        }
        return found;
    }

    private CallRoomEntity save(CallRoomEntity room) {
        if (room.getId() == null) {
            if (rowFor(room.getContextType(), room.getContextKey()).isPresent()) {
                // What Hibernate's SQLite dialect raises for the unique key (CallRoomPersistenceTest);
                // MySQL raises a DataIntegrityViolationException instead.
                throw new JpaSystemException(new PersistenceException("could not execute statement "
                        + "[[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed "
                        + "(UNIQUE constraint failed: call_room.context_type, call_room.context_key)]"));
            }
            room.setId(nextRoomId++);
        }
        roomRows.put(room.getId(), room);
        return room;
    }

    private Optional<CallRoomEntity> rowFor(CallContextType type, String key) {
        return roomRows.values().stream()
                .filter(room -> room.getContextType() == type && room.getContextKey().equals(key))
                .findFirst();
    }
}
