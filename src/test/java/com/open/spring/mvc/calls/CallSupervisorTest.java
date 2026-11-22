package com.open.spring.mvc.calls;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Keeping the rules during a call: pausing a mentor the moment the last teacher leaves, and back. */
class CallSupervisorTest {

    private static final String ROOM = "room-1";
    private static final CallParticipant S1 = new CallParticipant("id-s1", "s1", Set.of("ROLE_USER", "ROLE_STUDENT"));
    private static final CallParticipant M = new CallParticipant("id-m", "m", Set.of("ROLE_USER", "ROLE_STUDENT", "ROLE_MENTOR"));
    private static final CallParticipant T = new CallParticipant("id-t", "t", Set.of("ROLE_USER", "ROLE_TEACHER"));

    private final ManualCallScheduler scheduler = new ManualCallScheduler();
    private final LiveKitRooms rooms = mock(LiveKitRooms.class);
    private final CallPresence presence = new CallPresence();
    private CallPolicySettings policy;
    private CallSupervisor supervisor;

    @BeforeEach
    void setUp() {
        policy = new CallPolicySettings();
        policy.setSupervisorRoles(List.of("ROLE_TEACHER", "ROLE_ADMIN"));
        policy.setSupervisedRoles(List.of("ROLE_MENTOR"));
        policy.setMinorRoles(List.of("ROLE_STUDENT"));
        supervisor = new CallSupervisor(presence, policy, rooms, scheduler, scheduler.clock);
    }

    private void join(String sid, CallParticipant participant) {
        presence.joined(ROOM, sid, participant);
        supervisor.onPresenceChanged(ROOM);
        scheduler.runDue();
    }

    private void leave(String sid) {
        presence.left(ROOM, sid);
        supervisor.onPresenceChanged(ROOM);
        scheduler.runDue();
    }

    /** A teacher, a student and a mentor, in that order, so nobody is paused yet. */
    private void supervisedCall() {
        join("PA_t", T);
        join("PA_s1", S1);
        join("PA_m", M);
        verify(rooms, never()).updateParticipant(any(), any(), any());
    }

    @Test
    void supervisorLeavesThenSupervisedArePausedAtOnce() {
        supervisedCall();

        leave("PA_t");

        verify(rooms, times(1)).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
    }

    @Test
    void aMentorWithAStudentAndNoTeacherIsPausedWhenTheStudentArrives() {
        join("PA_m", M);
        verify(rooms, never()).updateParticipant(any(), any(), any());

        join("PA_s1", S1);

        verify(rooms).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
    }

    @Test
    void graceSettingDelaysThePause() {
        policy.setSupervisorGraceSeconds(60);
        supervisedCall();
        leave("PA_t");

        scheduler.advance(Duration.ofSeconds(59));
        verify(rooms, never()).updateParticipant(any(), any(), any());

        scheduler.advance(Duration.ofSeconds(1));
        verify(rooms, times(1)).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
    }

    @Test
    void aSupervisorBackWithinTheGraceMeansNoPause() {
        policy.setSupervisorGraceSeconds(60);
        supervisedCall();
        leave("PA_t");

        scheduler.advance(Duration.ofSeconds(30));
        join("PA_t2", T);
        scheduler.advance(Duration.ofSeconds(60));

        verify(rooms, never()).updateParticipant(any(), any(), any());
    }

    @Test
    void supervisorReturnResumesFullGrants() {
        supervisedCall();
        leave("PA_t");

        join("PA_t2", T);

        verify(rooms).updateParticipant(ROOM, M.identity(), CallGrants.FULL);
    }

    @Test
    void pauseFailureIsRetried() {
        doThrow(new CallException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE"))
                .doNothing()
                .when(rooms).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
        supervisedCall();

        leave("PA_t");
        verify(rooms, times(1)).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);

        scheduler.advance(Duration.ofSeconds(5));
        verify(rooms, times(2)).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);

        scheduler.advance(Duration.ofSeconds(30));
        verify(rooms, times(2)).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
    }

    @Test
    void someoneWhoLeftIsNotResumed() {
        supervisedCall();
        leave("PA_t");
        leave("PA_m");

        join("PA_t2", T);

        verify(rooms, never()).updateParticipant(ROOM, M.identity(), CallGrants.FULL);
    }

    @Test
    void aSlowLiveKitDoesNotHoldUpOtherCallers() throws Exception {
        java.util.concurrent.CountDownLatch inLiveKit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            inLiveKit.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(rooms).updateParticipant(any(), any(), any());
        presence.joined(ROOM, "PA_s1", S1);
        presence.joined(ROOM, "PA_m", M);
        Thread schedulerThread = new Thread(() -> supervisor.reconcile(ROOM));
        schedulerThread.start();
        org.junit.jupiter.api.Assertions.assertTrue(inLiveKit.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // While the scheduler's thread waits on LiveKit, a token request and a finished room don't wait with it.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            supervisor.isHeld(ROOM, M.identity());
            supervisor.forget("room-2");
        });

        release.countDown();
        schedulerThread.join(5000);
        verify(rooms).updateParticipant(ROOM, M.identity(), CallGrants.PAUSED);
    }

    @Test
    void aModeratorsPauseOutlastsTheTeacherComingBack() {
        supervisedCall();
        doNothing().when(rooms).updateParticipant(any(), any(), any());
        supervisor.holdPaused(ROOM, M.identity());
        leave("PA_t");

        join("PA_t2", T);

        verify(rooms, never()).updateParticipant(ROOM, M.identity(), CallGrants.FULL);
    }
}
