package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The nightly clean-up keeps attendance for calls.attendance-days only. */
class CallAttendanceServiceTest {

    private final CallAttendanceJpaRepository attendance = mock(CallAttendanceJpaRepository.class);
    private final CallsProperties properties = new CallsProperties();
    private final CallAttendanceService service = new CallAttendanceService(attendance, properties);

    @Test
    void rowsOlderThanTheWindowAreDeleted() {
        properties.setEnabled(true);
        properties.setAttendanceDays(30);

        service.deleteOldRows();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(attendance).deleteJoinedBefore(cutoff.capture());
        Duration age = Duration.between(cutoff.getValue(), Instant.now());
        assertTrue(age.compareTo(Duration.ofDays(30).minusSeconds(5)) > 0, age.toString());
        assertTrue(age.compareTo(Duration.ofDays(30).plusSeconds(5)) < 0, age.toString());
    }

    @Test
    void nothingRunsWhileCallsAreOff() {
        properties.setEnabled(false);

        service.deleteOldRows();

        verify(attendance, never()).deleteJoinedBefore(any());
    }
}
