package com.open.spring.mvc.calls;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CallAttendanceJpaRepository extends JpaRepository<CallAttendanceEntity, Long> {

    /** This person's open row in a room, if they are in it. */
    Optional<CallAttendanceEntity> findFirstByCallRoomIdAndIdentityAndLeftAtIsNull(Long callRoomId, String identity);

    List<CallAttendanceEntity> findByCallRoomIdAndLeftAtIsNull(Long callRoomId);

    @Modifying
    @Transactional
    @Query("DELETE FROM CallAttendanceEntity a WHERE a.joinedAt < :cutoff")
    int deleteJoinedBefore(@Param("cutoff") Instant cutoff);
}
