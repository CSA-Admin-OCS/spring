package com.open.spring.mvc.calls;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CallRoomJpaRepository extends JpaRepository<CallRoomEntity, Long> {

    /** The room of a group, DM or course; there is at most one. */
    Optional<CallRoomEntity> findByContextTypeAndContextKey(CallContextType contextType, String contextKey);

    /** The room LiveKit knows by this name (a UUID); webhooks name rooms this way. */
    Optional<CallRoomEntity> findByRoomName(String roomName);

    List<CallRoomEntity> findByState(CallState state);
}
