package com.open.spring.mvc.calls;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Attendance from LiveKit's webhooks: one open row per person in a room, closed when they leave. */
@Service
public class CallAttendanceService {

    private final CallAttendanceJpaRepository attendance;
    private final CallsProperties properties;

    public CallAttendanceService(CallAttendanceJpaRepository attendance, CallsProperties properties) {
        this.attendance = attendance;
        this.properties = properties;
    }

    /** A row for this person, unless they already have an open one (a second tab, or a retried webhook). */
    public void joined(CallRoomEntity room, CallParticipant participant) {
        if (attendance.findFirstByCallRoomIdAndIdentityAndLeftAtIsNull(room.getId(), participant.identity()).isEmpty()) {
            attendance.save(CallAttendanceEntity.joined(room.getId(), participant, Instant.now()));
        }
    }

    /** Called when this person's last connection to the room has left. */
    public void left(CallRoomEntity room, String identity) {
        attendance.findFirstByCallRoomIdAndIdentityAndLeftAtIsNull(room.getId(), identity).ifPresent(row -> {
            row.setLeftAt(Instant.now());
            attendance.save(row);
        });
    }

    public void roomFinished(CallRoomEntity room) {
        Instant now = Instant.now();
        List<CallAttendanceEntity> open = attendance.findByCallRoomIdAndLeftAtIsNull(room.getId());
        open.forEach(row -> row.setLeftAt(now));
        attendance.saveAll(open);
    }

    /** Every night, the rows of people who joined more than calls.attendance-days ago. */
    @Scheduled(cron = "0 30 3 * * *")
    public void deleteOldRows() {
        if (!properties.isEnabled()) {
            return; // the table isn't made while calls are off
        }
        attendance.deleteJoinedBefore(Instant.now().minus(Duration.ofDays(properties.getAttendanceDays())));
    }
}
