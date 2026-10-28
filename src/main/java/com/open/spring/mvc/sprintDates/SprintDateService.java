package com.open.spring.mvc.sprintDates;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.calendar.CalendarEventEntity;
import com.open.spring.mvc.calendar.CalendarEventJpaRepository;

/**
 * Service for managing sprint dates and their associated calendar events.
 * Handles calculation of week dates and automatic calendar event creation.
 */
@Service
public class SprintDateService {

    @Autowired
    private CalendarEventJpaRepository calendarEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Calculate the date for a specific week within the sprint.
     * 
     * @param sprintStart The start date of the sprint
     * @param sprintEnd The end date of the sprint
     * @param weekNum The week number to calculate
     * @param startWeek The first week number in the sprint
     * @param endWeek The last week number in the sprint
     * @param isEndDate If true, return the end date of the week; if false, return the start date
     * @return The calculated date
     */
    public LocalDate calculateWeekDate(LocalDate sprintStart, LocalDate sprintEnd,
                                        int weekNum, int startWeek, int endWeek,
                                        boolean isEndDate) {
        int totalWeeks = endWeek - startWeek + 1;
        int weekIndex = weekNum - startWeek;
        long totalDays = ChronoUnit.DAYS.between(sprintStart, sprintEnd);
        double daysPerWeek = (double) totalDays / totalWeeks;

        if (isEndDate) {
            LocalDate endDate = sprintStart.plusDays((long) Math.round((weekIndex + 1) * daysPerWeek) - 1);
            return endDate.isAfter(sprintEnd) ? sprintEnd : endDate;
        } else {
            return sprintStart.plusDays((long) Math.round(weekIndex * daysPerWeek));
        }
    }

    /**
     * Create all calendar events for a sprint.
     * This includes sprint start/end events, week events, and assignment due dates.
     * 
     * @param sprintDate The SprintDate entity
     * @return List of created calendar event IDs
     */
    public List<Long> createSprintCalendarEvents(SprintDate sprintDate) {
        List<Long> eventIds = new ArrayList<>();
        String course = sprintDate.getCourse().toUpperCase();
        String sprintKey = sprintDate.getSprintKey();
        String sprintTitle = sprintDate.getSprintTitle() != null ? sprintDate.getSprintTitle() : sprintKey;
        LocalDate startDate = sprintDate.getStartDate();
        LocalDate endDate = sprintDate.getEndDate();
        int startWeek = sprintDate.getStartWeek();
        int endWeek = sprintDate.getEndWeek();

        // 1. Create Sprint Start Event
        CalendarEventEntity startEvent = new CalendarEventEntity(
            startDate,
            "🚀 " + sprintKey + " Start: " + sprintTitle,
            course + " " + sprintKey + " begins.\nSprint: " + sprintTitle + "\nWeeks " + startWeek + "-" + endWeek,
            "sprint",
            course
        );
        CalendarEventEntity savedStartEvent = calendarEventRepository.save(startEvent);
        eventIds.add(savedStartEvent.getId());

        // 2. Create Sprint End Event
        CalendarEventEntity endEvent = new CalendarEventEntity(
            endDate,
            "🏁 " + sprintKey + " End: " + sprintTitle,
            course + " " + sprintKey + " ends.\nSprint: " + sprintTitle,
            "sprint",
            course
        );
        CalendarEventEntity savedEndEvent = calendarEventRepository.save(endEvent);
        eventIds.add(savedEndEvent.getId());

        // Parse week assignments
        Map<Integer, List<String>> assignments = parseWeekAssignments(sprintDate.getWeekAssignments());

        // 3. Create Week Events and Assignment Due Dates
        for (int weekNum = startWeek; weekNum <= endWeek; weekNum++) {
            LocalDate weekStartDate = calculateWeekDate(startDate, endDate, weekNum, startWeek, endWeek, false);
            LocalDate weekEndDate = calculateWeekDate(startDate, endDate, weekNum, startWeek, endWeek, true);

            // Week Start Event
            CalendarEventEntity weekEvent = new CalendarEventEntity(
                weekStartDate,
                "📅 Week " + weekNum + " - " + course,
                course + " " + sprintKey + " - Week " + weekNum,
                "week",
                course
            );
            CalendarEventEntity savedWeekEvent = calendarEventRepository.save(weekEvent);
            eventIds.add(savedWeekEvent.getId());

            // Assignment Due Dates for this week
            List<String> weekAssignments = assignments.getOrDefault(weekNum, Collections.emptyList());
            for (String assignment : weekAssignments) {
                CalendarEventEntity assignmentEvent = new CalendarEventEntity(
                    weekEndDate,
                    "📝 Due: " + assignment,
                    course + " Assignment Due\nWeek " + weekNum + " - " + sprintKey + "\n" + assignment,
                    "assignment",
                    course
                );
                CalendarEventEntity savedAssignmentEvent = calendarEventRepository.save(assignmentEvent);
                eventIds.add(savedAssignmentEvent.getId());
            }
        }

        return eventIds;
    }

    /**
     * Delete all calendar events associated with a sprint.
     * 
     * @param sprintDate The SprintDate entity containing the event IDs to delete
     */
    public void deleteSprintCalendarEvents(SprintDate sprintDate) {
        List<Long> eventIds = parseEventIds(sprintDate.getCalendarEventIds());
        for (Long eventId : eventIds) {
            try {
                if (calendarEventRepository.existsById(eventId)) {
                    calendarEventRepository.deleteById(eventId);
                }
            } catch (Exception e) {
                // Log but continue - event may have been manually deleted
                System.err.println("Could not delete calendar event " + eventId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Parse week assignments from JSON string.
     * 
     * @param weekAssignmentsJson JSON string like {"0": ["Task1", "Task2"], "1": ["Task3"]}
     * @return Map of week number to list of assignments
     */
    public Map<Integer, List<String>> parseWeekAssignments(String weekAssignmentsJson) {
        if (weekAssignmentsJson == null || weekAssignmentsJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            // First try to parse as Map<String, List<String>> since JSON keys are strings
            Map<String, List<String>> stringKeyMap = objectMapper.readValue(
                weekAssignmentsJson, 
                new TypeReference<Map<String, List<String>>>() {}
            );
            // Convert to Map<Integer, List<String>>
            Map<Integer, List<String>> result = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> entry : stringKeyMap.entrySet()) {
                try {
                    result.put(Integer.parseInt(entry.getKey()), entry.getValue());
                } catch (NumberFormatException e) {
                    // Skip invalid week numbers
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            System.err.println("Error parsing week assignments: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Parse calendar event IDs from JSON array string.
     * 
     * @param calendarEventIdsJson JSON array like [45, 46, 47]
     * @return List of event IDs
     */
    public List<Long> parseEventIds(String calendarEventIdsJson) {
        if (calendarEventIdsJson == null || calendarEventIdsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(calendarEventIdsJson, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            System.err.println("Error parsing event IDs: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Convert list of event IDs to JSON array string.
     * 
     * @param eventIds List of event IDs
     * @return JSON array string
     */
    public String eventIdsToJson(List<Long> eventIds) {
        try {
            return objectMapper.writeValueAsString(eventIds);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * Convert week assignments map to JSON string.
     * 
     * @param assignments Map of week number to list of assignments
     * @return JSON string
     */
    public String weekAssignmentsToJson(Map<Integer, List<String>> assignments) {
        try {
            // Convert Integer keys to String keys for proper JSON serialization
            Map<String, List<String>> stringKeyMap = new java.util.HashMap<>();
            for (Map.Entry<Integer, List<String>> entry : assignments.entrySet()) {
                stringKeyMap.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return objectMapper.writeValueAsString(stringKeyMap);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
