package com.open.spring.mvc.calls;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The call room of one group, DM conversation or course: made the first time someone opens it and
 * reused for every call after (context_type and context_key are unique together). roomName is the
 * LiveKit room's name, a random UUID, so it tells LiveKit nothing about who is in it.
 *
 * The table comes from CallRoomTableInitializer (ddl-auto=none). It already has the columns later
 * call features fill in (startedAt, endedAt), since CREATE TABLE IF NOT EXISTS can't add one later.
 */
@Entity
@Table(name = "call_room")
@Getter
@Setter
@NoArgsConstructor
public class CallRoomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private CallContextType contextType;

    /** The group's or DM conversation's id, or the course's class-group name (CSA). */
    @Column(length = 64, nullable = false)
    private String contextKey;

    /** One of calls.purposes. */
    @Column(length = 32, nullable = false)
    private String purpose;

    @Column(length = 64, nullable = false, unique = true)
    private String roomName;

    /** The group's or course's name; null for a DM, whose page names the conversation itself. */
    @Column(length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private CallState state;

    @Column(length = 255)
    private String createdByUid;

    private Instant createdAt;

    private Instant startedAt;

    private Instant endedAt;

    /** A new idle room for this context, opened first by this person. */
    public static CallRoomEntity forContext(CallContextType contextType, String contextKey, String createdByUid) {
        CallRoomEntity room = new CallRoomEntity();
        room.contextType = contextType;
        room.contextKey = contextKey;
        room.roomName = UUID.randomUUID().toString();
        room.state = CallState.IDLE;
        room.createdByUid = createdByUid;
        room.createdAt = Instant.now();
        return room;
    }
}
