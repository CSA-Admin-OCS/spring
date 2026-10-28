package com.open.spring.mvc.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

public class CalendarImportMsgTest {

    private static final String CSA_CHANNEL = "CRRJL1F1D";
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);

    @Test
    public void readsAWeeklyPlan() {
        String plan = String.join("\n",
                "Week of 10/26/2026",
                "[Mon]: Sprint review",
                "[Tue - Wed]: *Team check-ins",
                "• bring your kanban board",
                "[Fri]: **Unit 3 quiz");

        List<CalendarEventEntity> events = CalendarImportMsg.toEvents(plan, CSA_CHANNEL, THURSDAY);

        assertEquals(4, events.size());
        assertEvent(events.get(0), LocalDate.of(2026, 10, 26), "Sprint review", "daily plan", "");
        assertEvent(events.get(1), LocalDate.of(2026, 10, 27), "Team check-ins", "check-in", "bring your kanban board");
        assertEvent(events.get(2), LocalDate.of(2026, 10, 28), "Team check-ins", "check-in", "bring your kanban board");
        assertEvent(events.get(3), LocalDate.of(2026, 10, 30), "Unit 3 quiz", "grade", "");
        assertTrue(events.stream().allMatch(e -> "CSA".equals(e.getPeriod())));
    }

    @Test
    public void bulletsAddToTheDescriptionAndCanChangeTheType() {
        String plan = String.join("\n",
                "Week of 10/26/2026",
                "[Thu]: Lesson",
                "• slides",
                "** • graded exit ticket");

        CalendarEventEntity event = CalendarImportMsg.toEvents(plan, CSA_CHANNEL, THURSDAY).get(0);

        assertEquals("slides, graded exit ticket", event.getDescription());
        assertEquals("grade", event.getType());
    }

    @Test
    public void weekStartReadsEachWeekOfForm() {
        assertEquals(LocalDate.of(2026, 10, 26), CalendarImportMsg.weekStart("Week of 10/26/2026", THURSDAY));
        assertEquals(LocalDate.of(2026, 11, 2), CalendarImportMsg.weekStart("Week of 11/2", THURSDAY));
        assertEquals(LocalDate.of(2026, 10, 26), CalendarImportMsg.weekStart("Week of the 26th", THURSDAY));
    }

    @Test
    public void withoutAWeekOfLineOrWithAnImpossibleDateItUsesThisWeek() {
        LocalDate thisWeek = LocalDate.of(2026, 10, 18); // the Sunday ending Thursday's week
        assertEquals(thisWeek, CalendarImportMsg.weekStart("[Mon]: Sprint review", THURSDAY));
        assertEquals(thisWeek, CalendarImportMsg.weekStart("Week of 13/40", THURSDAY));

        CalendarEventEntity event = CalendarImportMsg.toEvents("[Mon]: Sprint review", CSA_CHANNEL, THURSDAY).get(0);
        assertEquals(LocalDate.of(2026, 10, 12), event.getDate());
    }

    @Test
    public void ordinaryChatAndOtherChannelsAreSafe() {
        assertTrue(CalendarImportMsg.toEvents("anyone have the link?", CSA_CHANNEL, THURSDAY).isEmpty());
        assertTrue(CalendarImportMsg.toEvents(null, CSA_CHANNEL, THURSDAY).isEmpty());
        assertEquals("0", CalendarImportMsg.toEvents("[Mon]: Sprint review", null, THURSDAY).get(0).getPeriod());
        assertEquals("0", CalendarImportMsg.toEvents("[Mon]: Sprint review", "C0OTHER", THURSDAY).get(0).getPeriod());
    }

    private static void assertEvent(CalendarEventEntity event, LocalDate date, String title, String type, String description) {
        assertEquals(date, event.getDate());
        assertEquals(title, event.getTitle());
        assertEquals(type, event.getType());
        assertEquals(description, event.getDescription());
    }
}
