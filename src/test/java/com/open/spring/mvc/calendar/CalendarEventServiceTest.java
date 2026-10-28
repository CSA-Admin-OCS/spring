package com.open.spring.mvc.calendar;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CalendarEventServiceTest {

    @Mock
    private CalendarEventJpaRepository calendarEventRepository;

    @InjectMocks
    private CalendarEventService eventService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testFilterEvents_byTypeAndDateRange() {
        CalendarEventEntity e1 = new CalendarEventEntity(LocalDate.of(2026,5,1), "Title A", "desc", "meeting", "P1");
        CalendarEventEntity e2 = new CalendarEventEntity(LocalDate.of(2026,5,2), "Title B", "desc", "event", "P2");

        when(calendarEventRepository.findAll()).thenReturn(List.of(e1, e2));

        List<CalendarEventEntity> res = eventService.filterEvents("meeting", null, null, null, null, null);
        assertEquals(1, res.size());
        assertEquals("Title A", res.get(0).getTitle());
    }
}
