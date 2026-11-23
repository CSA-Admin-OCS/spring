package com.open.spring.mvc.calls;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Who is connected to each room right now, and who was removed from it, in memory (like
 * GroupChatPresenceService), filled from LiveKit's webhooks. LiveKit reports each connection by its
 * sid: one person can have two (two tabs), and LiveKit may retry or reorder its reports, so a
 * connection that already left can't come back, and people are counted once by identity. It also
 * remembers whose identity is whose from the tokens Spring gave out, since an identity can't be
 * turned back into a uid.
 */
@Component
public class CallPresence {

    static final int MAX_ENDED_CONNECTIONS_PER_ROOM = 10_000;

    private final Map<String, Map<String, CallParticipant>> connections = new HashMap<>();
    private final Map<String, Set<String>> leftConnections = new HashMap<>();
    private final Map<String, Set<String>> removedIdentities = new HashMap<>();
    private final Map<String, String> uidsByIdentity = new HashMap<>();

    /** True when the connection now counts as present; false for a late join of one that already left. */
    public synchronized boolean joined(String room, String sid, CallParticipant participant) {
        if (leftConnections.getOrDefault(room, Set.of()).contains(sid)) {
            return false;
        }
        connections.computeIfAbsent(room, key -> new LinkedHashMap<>()).put(sid, participant);
        return true;
    }

    public synchronized void left(String room, String sid) {
        markLeft(room, sid);
        Map<String, CallParticipant> sids = connections.get(room);
        if (sids != null) {
            sids.remove(sid);
        }
    }

    /** Everyone connected to the room, once each however many connections they have. */
    public synchronized Collection<CallParticipant> present(String room) {
        Map<String, CallParticipant> byIdentity = new LinkedHashMap<>();
        connections.getOrDefault(room, Map.of()).values()
                .forEach(participant -> byIdentity.putIfAbsent(participant.identity(), participant));
        return List.copyOf(byIdentity.values());
    }

    public synchronized void markRemoved(String room, String identity) {
        removedIdentities.computeIfAbsent(room, key -> new HashSet<>()).add(identity);
    }

    public synchronized Set<String> removed(String room) {
        return Set.copyOf(removedIdentities.getOrDefault(room, Set.of()));
    }

    public synchronized void rememberToken(String identity, String uid) {
        uidsByIdentity.put(identity, uid);
    }

    /** The uid a token with this identity was made for, or null if Spring restarted since. */
    public synchronized String uidOf(String identity) {
        return uidsByIdentity.get(identity);
    }

    /**
     * The room finished: its connections and removals end with it. The connections are kept as
     * ended, so a join LiveKit retries from that call can't bring anyone back.
     */
    public synchronized void clear(String room) {
        Map<String, CallParticipant> sids = connections.remove(room);
        if (sids != null) {
            sids.keySet().forEach(sid -> markLeft(room, sid));
        }
        removedIdentities.remove(room);
    }

    // LiveKit retries a webhook for minutes, so only recent ended connections need remembering.
    private void markLeft(String room, String sid) {
        Set<String> left = leftConnections.computeIfAbsent(room, key -> new LinkedHashSet<>());
        left.add(sid);
        if (left.size() > MAX_ENDED_CONNECTIONS_PER_ROOM) {
            Iterator<String> oldest = left.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
