package com.open.spring.mvc.calls;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CallRoomJpaRepository extends JpaRepository<CallRoomEntity, Long> {

    /** The room of a group, DM or course; there is at most one. */
    Optional<CallRoomEntity> findByContextTypeAndContextKey(CallContextType contextType, String contextKey);
}
