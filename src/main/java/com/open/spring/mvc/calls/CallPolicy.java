package com.open.spring.mvc.calls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.open.spring.mvc.calls.CallDecision.Outcome;
import com.open.spring.mvc.calls.CallPolicySettings.OneAdultOneMinor;

/**
 * The safety rules for adults and minors in a call, read from CallPolicySettings:
 * a supervised role (a mentor) may be with minors only while a supervisor (a teacher) is
 * connected; a call of one adult and one minor is listed, or waits for a second adult; and a
 * removed person gets no new token. Pure functions, so the same rules decide a join and a pause.
 */
public final class CallPolicy {

    /**
     * How the rules see someone: the first kind their roles match, in this order. Every account
     * starts with the minor role, so a teacher or mentor who also has it is still a teacher or mentor.
     */
    private enum Kind { SUPERVISOR, SUPERVISED, MINOR, OTHER }

    private CallPolicy() {
    }

    /** Whether this person may join a room with these people in it, and with which grants. */
    public static CallDecision decide(CallPolicySettings settings, Collection<CallParticipant> present,
            CallParticipant joining, Set<String> removed) {
        if (removed.contains(joining.identity())) {
            return CallDecision.refused(Outcome.DENY, "REMOVED");
        }
        // Someone opening a second tab is already present; count them once.
        List<CallParticipant> others = present.stream()
                .filter(participant -> !participant.identity().equals(joining.identity()))
                .toList();
        // A supervised person and a minor are never connected without a supervisor: whichever of
        // them comes second waits, so neither sees or hears the other before Spring could pause anyone.
        Kind kind = kindOf(settings, joining);
        boolean unsupervisedPair = (kind == Kind.SUPERVISED && has(settings, others, Kind.MINOR))
                || (kind == Kind.MINOR && has(settings, others, Kind.SUPERVISED));
        if (unsupervisedPair && !has(settings, others, Kind.SUPERVISOR)) {
            return CallDecision.refused(Outcome.WAIT, "WAITING_FOR_SUPERVISOR");
        }
        List<CallParticipant> after = new ArrayList<>(others);
        after.add(joining);
        boolean oneOnOne = isOneAdultWithOneMinor(settings, after);
        if (oneOnOne && settings.getOneAdultOneMinor() == OneAdultOneMinor.REQUIRE_SECOND_ADULT) {
            return CallDecision.refused(Outcome.WAIT, "WAITING_FOR_SECOND_ADULT");
        }
        return new CallDecision(Outcome.ALLOW, null, CallGrants.FULL, oneOnOne);
    }

    /** The supervised people to pause now: minors are connected and no supervisor is. */
    public static Set<String> mustPause(CallPolicySettings settings, Collection<CallParticipant> present) {
        if (!has(settings, present, Kind.MINOR) || has(settings, present, Kind.SUPERVISOR)) {
            return Set.of();
        }
        return present.stream()
                .filter(participant -> kindOf(settings, participant) == Kind.SUPERVISED)
                .map(CallParticipant::identity)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean canModerate(CallPolicySettings settings, CallParticipant participant) {
        return holdsAny(participant, settings.getModeratorRoles());
    }

    /** Exactly two people: one adult (a supervisor or a supervised role) and one minor. */
    public static boolean isOneAdultWithOneMinor(CallPolicySettings settings, Collection<CallParticipant> people) {
        if (people.size() != 2) {
            return false;
        }
        long minors = people.stream().filter(participant -> kindOf(settings, participant) == Kind.MINOR).count();
        long adults = people.stream().filter(participant -> isAdult(kindOf(settings, participant))).count();
        return minors == 1 && adults == 1;
    }

    private static boolean isAdult(Kind kind) {
        return kind == Kind.SUPERVISOR || kind == Kind.SUPERVISED;
    }

    private static boolean has(CallPolicySettings settings, Collection<CallParticipant> people, Kind kind) {
        return people.stream().anyMatch(participant -> kindOf(settings, participant) == kind);
    }

    private static Kind kindOf(CallPolicySettings settings, CallParticipant participant) {
        if (holdsAny(participant, settings.getSupervisorRoles())) {
            return Kind.SUPERVISOR;
        }
        if (holdsAny(participant, settings.getSupervisedRoles())) {
            return Kind.SUPERVISED;
        }
        if (holdsAny(participant, settings.getMinorRoles())) {
            return Kind.MINOR;
        }
        return Kind.OTHER;
    }

    private static boolean holdsAny(CallParticipant participant, List<String> roles) {
        return roles.stream().anyMatch(participant.roles()::contains);
    }
}
