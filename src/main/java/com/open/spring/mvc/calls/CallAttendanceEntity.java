package com.open.spring.mvc.calls;

import java.time.Instant;
import java.util.TreeSet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Who was in a call: one row per person each time they join a room, open (leftAt null) until their
 * last connection leaves. Kept calls.attendance-days, then deleted (CallAttendanceService). The
 * table comes from CallAttendanceTableInitializer (ddl-auto=none).
 */
@Entity
@Table(name = "call_attendance")
@Getter
@Setter
@NoArgsConstructor
public class CallAttendanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long callRoomId;

    /** Their LiveKit identity, which webhooks name them by. */
    @Column(length = 32, nullable = false)
    private String identity;

    /** Null when Spring restarted between giving them a token and their join. */
    @Column(length = 255)
    private String uid;

    /** The role names their token listed, comma-separated. */
    @Column(length = 255)
    private String roles;

    @Column(nullable = false)
    private Instant joinedAt;

    private Instant leftAt;

    public static CallAttendanceEntity joined(Long callRoomId, CallParticipant participant, Instant at) {
        CallAttendanceEntity row = new CallAttendanceEntity();
        row.callRoomId = callRoomId;
        row.identity = participant.identity();
        row.uid = participant.uid();
        row.roles = String.join(",", new TreeSet<>(participant.roles()));
        row.joinedAt = at;
        return row;
    }
}
