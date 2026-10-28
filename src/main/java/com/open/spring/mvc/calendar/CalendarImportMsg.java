package com.open.spring.mvc.calendar;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Slack weekly-plan message into calendar events. SlackController hands every
 * Slack message to POST /api/calendar/add, and CalendarEventService saves what this returns.
 *
 * A plan looks like this:
 *
 *   Week of 10/26/2026
 *   [Mon]: Sprint review
 *   [Tue - Wed]: *Team check-ins
 *   • bring your kanban board
 *
 * Each [Day] or [Day - Day] line becomes one event per day. A bullet line adds to the
 * description of the day line above it. One * marks a check-in, ** a grade, and anything
 * else is a daily plan. The Slack channel decides the course (period); other channels get "0".
 */
public final class CalendarImportMsg {

    private static final Map<String, String> CHANNEL_COURSES = Map.of(
            "CUS8E3M6Z", "CSP",
            "CRRJL1F1D", "CSA",
            "C05MNRWC2A1", "CSSE");

    private static final List<String> DAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
    private static final Pattern DAY_LINE = Pattern.compile(
            "\\[(Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?: - (Mon|Tue|Wed|Thu|Fri|Sat|Sun))?\\]:\\s*(\\*\\*|\\*)?\\s*(.+)");
    private static final Pattern BULLET_LINE = Pattern.compile("(\\*\\*|\\*)?\\s*\\u2022\\s*(.+)");

    private static final Pattern FULL_DATE = Pattern.compile("Week of (\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern MONTH_DAY = Pattern.compile("Week of (\\d{1,2})/(\\d{1,2})");
    private static final Pattern ORDINAL_DAY = Pattern.compile("Week of the (\\d{1,2})(st|nd|rd|th)");

    private CalendarImportMsg() {
    }

    /**
     * The events in one Slack message, not yet saved. A message with no day lines (most chat)
     * gives an empty list. today fills in what the "Week of" line leaves out.
     */
    public static List<CalendarEventEntity> toEvents(String text, String channel, LocalDate today) {
        List<CalendarEventEntity> events = new ArrayList<>();
        if (text == null) {
            return events;
        }
        LocalDate weekStart = weekStart(text, today);
        String period = CHANNEL_COURSES.getOrDefault(channel == null ? "" : channel, "0");

        CalendarEventEntity lastEvent = null;
        List<CalendarEventEntity> lastEventRange = new ArrayList<>();
        for (String line : text.split("\\n")) {
            Matcher dayMatcher = DAY_LINE.matcher(line);
            if (dayMatcher.find()) {
                String startDay = dayMatcher.group(1);
                String endDay = dayMatcher.group(2) != null ? dayMatcher.group(2) : startDay;
                String title = dayMatcher.group(4).trim();
                String type = typeFor(dayMatcher.group(3), "daily plan");

                Matcher bulletMatcher = BULLET_LINE.matcher(line);
                String description = bulletMatcher.find() ? bulletMatcher.group(2).trim() : "";

                lastEventRange.clear();
                for (LocalDate date : datesInRange(startDay, endDay, weekStart)) {
                    CalendarEventEntity event = new CalendarEventEntity(date, title, description, type, period);
                    events.add(event);
                    lastEvent = event;
                    lastEventRange.add(event);
                }
            } else {
                Matcher bulletMatcher = BULLET_LINE.matcher(line);
                if (bulletMatcher.find() && !lastEventRange.isEmpty()) {
                    String description = bulletMatcher.group(2).trim();
                    String type = typeFor(bulletMatcher.group(1), lastEvent.getType());
                    for (CalendarEventEntity event : lastEventRange) {
                        event.setDescription(event.getDescription()
                                + (event.getDescription().isEmpty() ? "" : ", ") + description);
                        event.setType(type);
                    }
                }
            }
        }
        return events;
    }

    /**
     * The week a message is about: "Week of 10/26/2026", "Week of 10/26" (this year) or
     * "Week of the 26th" (this month). Without one, or with an impossible date, it's this week.
     */
    static LocalDate weekStart(String text, LocalDate today) {
        try {
            Matcher m = FULL_DATE.matcher(text);
            if (m.find()) {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
            m = MONTH_DAY.matcher(text);
            if (m.find()) {
                return LocalDate.of(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
            m = ORDINAL_DAY.matcher(text);
            if (m.find()) {
                return LocalDate.of(today.getYear(), today.getMonthValue(), Integer.parseInt(m.group(1)));
            }
        } catch (DateTimeException e) {
            // An impossible date such as 13/40: fall back to this week.
        }
        return today.with(DayOfWeek.SUNDAY);
    }

    /** * is a check-in and ** a grade; no marker keeps the fallback type. */
    private static String typeFor(String asterisks, String fallback) {
        if ("*".equals(asterisks)) return "check-in";
        if ("**".equals(asterisks)) return "grade";
        return fallback;
    }

    /** Each date from startDay to endDay in the Monday-to-Sunday week that contains weekStart. */
    private static List<LocalDate> datesInRange(String startDay, String endDay, LocalDate weekStart) {
        int startIndex = DAYS.indexOf(startDay);
        int endIndex = DAYS.indexOf(endDay);

        List<LocalDate> dates = new ArrayList<>();
        if (startIndex != -1 && endIndex != -1) {
            for (int i = startIndex; i <= endIndex; i++) {
                dates.add(weekStart.plusDays(i - weekStart.getDayOfWeek().getValue() + 1));
            }
        }
        return dates;
    }
}
