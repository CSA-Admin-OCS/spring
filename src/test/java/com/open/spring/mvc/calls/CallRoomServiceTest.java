package com.open.spring.mvc.calls;

import static com.open.spring.mvc.calls.CallContextType.COURSE;
import static com.open.spring.mvc.calls.CallContextType.DM;
import static com.open.spring.mvc.calls.CallContextType.GROUP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.open.spring.mvc.person.Person;

/** Who may join a room, and how a room is found or made for a group, DM or course. */
class CallRoomServiceTest {

    private CallTestStore store;
    private CallRoomService service;
    private Person ada;        // in group 12 and DM 34
    private Person niko;       // in neither
    private Person teacher;
    private Person csaStudent; // in the CSA class group
    private Person cspOnly;    // in the CSP class group, and DM 34

    @BeforeEach
    void setUp() {
        store = new CallTestStore();
        service = store.service;
        ada = store.person(1, "ada", "ROLE_USER", "ROLE_STUDENT");
        niko = store.person(2, "niko", "ROLE_USER", "ROLE_STUDENT");
        teacher = store.person(3, "mort", "ROLE_USER", "ROLE_TEACHER");
        csaStudent = store.person(4, "grace", "ROLE_USER", "ROLE_STUDENT");
        cspOnly = store.person(5, "linus", "ROLE_USER", "ROLE_STUDENT");
        store.group(12, "Binary Beasts", ada);
        store.group(70, "CSA", csaStudent);
        store.group(71, "CSP", cspOnly);
        store.conversation(34, ada, cspOnly);
    }

    private static CallRoomEntity room(CallContextType type, String key) {
        return CallRoomEntity.forContext(type, key, "someone");
    }

    private static void assertRefused(HttpStatus status, String reason, CallException refused) {
        assertEquals(status, refused.getStatus());
        assertEquals(reason, refused.getReason());
    }

    // ---- who may join ----

    @Test
    void groupMemberCanJoinTheGroupsRoom() {
        assertTrue(service.canJoin(ada, room(GROUP, "12")));
    }

    @Test
    void nonMemberCannotJoinAGroupsRoom() {
        assertFalse(service.canJoin(niko, room(GROUP, "12")));
    }

    @Test
    void dmParticipantCanJoinTheDmsRoom() {
        assertTrue(service.canJoin(ada, room(DM, "34")));
        assertTrue(service.canJoin(cspOnly, room(DM, "34")));
    }

    @Test
    void someoneOutsideTheDmCannotJoinItsRoom() {
        assertFalse(service.canJoin(niko, room(DM, "34")));
    }

    @Test
    void teacherCanJoinAnyRoom() {
        assertTrue(service.canJoin(teacher, room(GROUP, "12")));
        assertTrue(service.canJoin(teacher, room(DM, "34")));
        assertTrue(service.canJoin(teacher, room(COURSE, "CSA")));
    }

    @Test
    void courseRoomNeedsTheCourseClassGroup() {
        assertTrue(service.canJoin(csaStudent, room(COURSE, "CSA")));
        assertFalse(service.canJoin(cspOnly, room(COURSE, "CSA")));
    }

    @Test
    void supervisorRolesComeFromSettings() {
        store.policy.setSupervisorRoles(List.of("ROLE_ADMIN"));
        Person admin = store.person(6, "toby", "ROLE_USER", "ROLE_ADMIN");

        assertFalse(service.canJoin(teacher, room(GROUP, "12")));
        assertTrue(service.canJoin(admin, room(GROUP, "12")));
    }

    // ---- opening a room ----

    @Test
    void openForReturnsTheSameRoomTwice() {
        assertEquals(service.openFor(ada, GROUP, "12", null).getId(), service.openFor(ada, GROUP, "12", null).getId());
        assertEquals(1, store.roomCount());
    }

    @Test
    void openForRefusesANonMember() {
        assertRefused(HttpStatus.FORBIDDEN, "NOT_A_MEMBER",
                assertThrows(CallException.class, () -> service.openFor(niko, GROUP, "12", null)));
        assertEquals(0, store.roomCount());
    }

    @Test
    void roomNameIsAnOpaqueUuid() {
        CallRoomEntity groupRoom = service.openFor(ada, GROUP, "12", null);
        CallRoomEntity dmRoom = service.openFor(ada, DM, "34", null);

        assertDoesNotThrow(() -> UUID.fromString(groupRoom.getRoomName()));
        assertNotEquals(groupRoom.getRoomName(), dmRoom.getRoomName());
    }

    @Test
    void openForRefusesAContextThatDoesNotExist() {
        for (String key : List.of("999", "12abc", "-12", "012", "")) {
            assertRefused(HttpStatus.NOT_FOUND, "UNKNOWN_CONTEXT",
                    assertThrows(CallException.class, () -> service.openFor(teacher, GROUP, key, null), key));
        }
        assertRefused(HttpStatus.NOT_FOUND, "UNKNOWN_CONTEXT",
                assertThrows(CallException.class, () -> service.openFor(teacher, GROUP, null, null)));
        assertRefused(HttpStatus.NOT_FOUND, "UNKNOWN_CONTEXT",
                assertThrows(CallException.class, () -> service.openFor(teacher, DM, "35", null)));
        assertRefused(HttpStatus.NOT_FOUND, "UNKNOWN_CONTEXT",
                assertThrows(CallException.class, () -> service.openFor(teacher, COURSE, "CSH", null)));
        assertEquals(0, store.roomCount());
    }

    @Test
    void courseKeyIsTheClassGroupsName() {
        CallRoomEntity lower = service.openFor(csaStudent, COURSE, "csa", null);
        CallRoomEntity upper = service.openFor(csaStudent, COURSE, "CSA", null);

        assertEquals(lower.getId(), upper.getId());
        assertEquals("CSA", upper.getContextKey());
        assertEquals("CSA", upper.getTitle());
    }

    @Test
    void titleNamesTheGroupButADmRoomHasNone() {
        assertEquals("Binary Beasts", service.openFor(ada, GROUP, "12", null).getTitle());
        assertNull(service.openFor(ada, DM, "34", null).getTitle());
    }

    // ---- purposes ----

    @Test
    void noPurposeMeansTheFirstOneListed() {
        assertEquals("meeting", service.openFor(ada, GROUP, "12", null).getPurpose());

        store.properties.setPurposes(List.of("standup", "meeting"));
        assertEquals("standup", service.openFor(ada, GROUP, "12", "").getPurpose());
    }

    @Test
    void openForRefusesAPurposeNotInSettings() {
        assertRefused(HttpStatus.BAD_REQUEST, "UNKNOWN_PURPOSE",
                assertThrows(CallException.class, () -> service.openFor(ada, GROUP, "12", "party")));
        assertEquals(0, store.roomCount());
    }

    @Test
    void reopeningAnIdleRoomChangesItsPurpose() {
        assertEquals("standup", service.openFor(ada, GROUP, "12", "standup").getPurpose());
        assertEquals("meeting", service.openFor(ada, GROUP, "12", "meeting").getPurpose());
    }

    @Test
    void aLiveCallKeepsThePurposeItStartedWith() {
        CallRoomEntity room = service.openFor(ada, GROUP, "12", "standup");
        room.setState(CallState.LIVE); // set by LiveKit's webhooks in a later change

        assertEquals("standup", service.openFor(ada, GROUP, "12", "meeting").getPurpose());
    }
}
