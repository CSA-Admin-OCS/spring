package com.open.spring.mvc.calls;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Keeps the safety rules during a call. After each change in who is connected it pauses the
 * people CallPolicy.mustPause names (no publish, no subscribe), at once or after
 * calls.policy.supervisor-grace-seconds, and gives them full grants again when a supervisor is back.
 * A moderator's pause lasts until the moderator ends it. The work runs on CallScheduler, and a
 * LiveKit failure is tried again in a few seconds.
 */
@Service
public class CallSupervisor {

    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(5);

    private static final Logger logger = LoggerFactory.getLogger(CallSupervisor.class);

    /** What Spring has done to the people in one room. */
    private static final class Watch {
        final Set<String> paused = new HashSet<>();
        final Set<String> heldByModerator = new HashSet<>();
        Instant unsupervisedSince;
    }

    private final CallPresence presence;
    private final CallPolicySettings policy;
    private final LiveKitRooms liveKit;
    private final CallScheduler scheduler;
    private final Clock clock;
    private final Map<String, Watch> watches = new HashMap<>();

    @Autowired
    public CallSupervisor(CallPresence presence, CallPolicySettings policy, LiveKitRooms liveKit, CallScheduler scheduler) {
        this(presence, policy, liveKit, scheduler, Clock.systemUTC());
    }

    CallSupervisor(CallPresence presence, CallPolicySettings policy, LiveKitRooms liveKit, CallScheduler scheduler,
            Clock clock) {
        this.presence = presence;
        this.policy = policy;
        this.liveKit = liveKit;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** Called after a webhook changes who is in the room; the pausing itself runs on the scheduler. */
    public void onPresenceChanged(String room) {
        scheduler.schedule(() -> reconcile(room), Duration.ZERO);
    }

    /** A moderator paused this person: keep them paused until releaseHold. */
    public synchronized void holdPaused(String room, String identity) {
        Watch watch = watch(room);
        watch.heldByModerator.add(identity);
        watch.paused.add(identity);
    }

    /** Ends a moderator's pause. False when the safety rules still keep the person paused. */
    public synchronized boolean releaseHold(String room, String identity) {
        Watch watch = watch(room);
        watch.heldByModerator.remove(identity);
        if (CallPolicy.mustPause(policy, presence.present(room)).contains(identity)) {
            return false;
        }
        watch.paused.remove(identity);
        return true;
    }

    public synchronized boolean isHeld(String room, String identity) {
        Watch watch = watches.get(room);
        return watch != null && watch.heldByModerator.contains(identity);
    }

    /** The room finished, so nobody in it needs watching. */
    public synchronized void forget(String room) {
        watches.remove(room);
    }

    /** What to send to LiveKit for one room, worked out while holding the lock. */
    private record Plan(List<String> resume, List<String> pause) {}

    void reconcile(String room) {
        Plan plan = plan(room);
        // LiveKit is called without the lock, so a slow LiveKit holds up only this thread, not a
        // token request or a webhook.
        List<String> resumed = new ArrayList<>();
        List<String> paused = new ArrayList<>();
        boolean failed = false;
        for (String identity : plan.resume()) {
            if (send(room, identity, CallGrants.FULL)) {
                resumed.add(identity);
            } else {
                failed = true;
            }
        }
        for (String identity : plan.pause()) {
            if (send(room, identity, CallGrants.PAUSED)) {
                paused.add(identity);
            } else {
                failed = true;
            }
        }
        record(room, resumed, paused);
        if (failed) {
            scheduler.schedule(() -> reconcile(room), RETRY_AFTER_FAILURE);
        }
    }

    private synchronized Plan plan(String room) {
        Watch watch = watch(room);
        Collection<CallParticipant> present = presence.present(room);
        Set<String> here = present.stream().map(CallParticipant::identity).collect(Collectors.toSet());
        Set<String> byRules = CallPolicy.mustPause(policy, present);
        Instant now = clock.instant();
        if (byRules.isEmpty()) {
            watch.unsupervisedSince = null;
        } else if (watch.unsupervisedSince == null) {
            watch.unsupervisedSince = now;
        }
        watch.paused.retainAll(here); // anyone who left gets their grants from a new token
        List<String> resume = watch.paused.stream()
                .filter(identity -> !byRules.contains(identity) && !watch.heldByModerator.contains(identity))
                .sorted()
                .toList();
        List<String> pause = byRules.stream().filter(identity -> !watch.paused.contains(identity)).sorted().toList();
        if (!pause.isEmpty()) {
            Instant due = watch.unsupervisedSince.plusSeconds(policy.getSupervisorGraceSeconds());
            if (now.isBefore(due)) {
                scheduler.schedule(() -> reconcile(room), Duration.between(now, due));
                pause = List.of();
            }
        }
        return new Plan(resume, pause);
    }

    private synchronized void record(String room, List<String> resumed, List<String> paused) {
        Watch watch = watch(room);
        resumed.forEach(watch.paused::remove);
        watch.paused.addAll(paused);
    }

    private boolean send(String room, String identity, CallGrants grants) {
        try {
            liveKit.updateParticipant(room, identity, grants);
            return true;
        } catch (CallException e) {
            logger.warn("LiveKit is unavailable, so a call participant's permissions will be set again in {} s",
                    RETRY_AFTER_FAILURE.toSeconds());
            return false;
        }
    }

    private Watch watch(String room) {
        return watches.computeIfAbsent(room, key -> new Watch());
    }
}
