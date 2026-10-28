package com.open.spring.mvc.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calendar's rules: duplicate detection by title and date, who can see an
 * event, filters, breaks (and moving a break day's events to the next school day),
 * and saving the events CalendarImportMsg reads from a Slack message. Works only with
 * CalendarEventEntity; the controller converts to and from its DTO records.
 */
@Service
public class CalendarEventService {

    @Autowired
    private CalendarEventJpaRepository calendarEventRepository;

    private static final int MAX_DAYS_AHEAD = 365;

    // Save a new event
    public CalendarEventEntity saveEvent(CalendarEventEntity event) {
        CalendarEventEntity savedEvent = calendarEventRepository.save(event);
        return savedEvent;
    }

    // Find event by title and date (for duplicate detection)
    public CalendarEventEntity findByTitleAndDate(String title, LocalDate date) {
        return calendarEventRepository.findByTitleAndDate(title, date).orElse(null);
    }

    public CalendarEventEntity findByTitleAndDateAndIndividual(String title, LocalDate date, String individual) {
        return calendarEventRepository.findByTitleAndDateAndIndividual(title, date, individual).orElse(null);
    }

    // Create a calendar event
    public void createCalendarEvent(String title, LocalDate eventDate, String description, String type, String period) {
        CalendarEventEntity event = new CalendarEventEntity();
        event.setTitle(title);
        event.setDate(eventDate);
        event.setDescription(description);
        event.setType(type);
        event.setPeriod(period);
        calendarEventRepository.save(event);
    }

    // Get events by a specific date
    public List<CalendarEventEntity> getEventsByDate(LocalDate date) {
        return calendarEventRepository.findByDate(date);
    }

    public List<CalendarEventEntity> getEventsVisibleToIndividual(String individual) {
        List<CalendarEventEntity> events = calendarEventRepository.findAll();
        if (individual == null || individual.isBlank()) {
            return events;
        }

        return events.stream()
                .filter(event -> event.getIndividual() == null || event.getIndividual().isBlank() || individual.equalsIgnoreCase(event.getIndividual()))
                .toList();
    }

    public List<CalendarEventEntity> getEventsByDateVisibleToIndividual(LocalDate date, String individual) {
        return getEventsVisibleToIndividual(individual).stream()
                .filter(event -> date.equals(event.getDate()))
                .toList();
    }

    public List<CalendarEventEntity> getEventsWithinDateRangeVisibleToIndividual(LocalDate startDate, LocalDate endDate, String individual) {
        return getEventsVisibleToIndividual(individual).stream()
                .filter(event -> !event.getDate().isBefore(startDate) && !event.getDate().isAfter(endDate))
                .toList();
    }

    // Update event by id
    public boolean updateEventById(int id, String newTitle, String description, LocalDate date, String period) {
        CalendarEventEntity event = getEventById(id);
        if (event != null) {
            event.setTitle(newTitle);
            event.setDescription(description);
            event.setDate(date);
            event.setPeriod(period);
            calendarEventRepository.save(event);
            return true;
        }
        return false;
    }

    // Update event by id with appointment fields
    public boolean updateEventById(int id, String newTitle, String description, LocalDate date, String period,
                                   String type, String classPeriod, String groupName, String individual) {
        CalendarEventEntity event = getEventById(id);
        if (event != null) {
            event.setTitle(newTitle);
            event.setDescription(description);
            event.setDate(date);
            event.setPeriod(period);
            event.setType(type != null ? type : "event");
            event.setClassPeriod(classPeriod);
            event.setGroupName(groupName);
            event.setIndividual(individual);
            calendarEventRepository.save(event);
            return true;
        }
        return false;
    }

    // Count appointments by date and classPeriod
    public long countAppointmentsByDateAndClassPeriod(LocalDate date, String classPeriod) {
        return calendarEventRepository.countAppointmentsByDateAndClassPeriod(date, classPeriod);
    }

    // Delete event by id
    public boolean deleteEventById(int id) {
        CalendarEventEntity event = getEventById(id);
        if (event != null) {
            calendarEventRepository.delete(event);
            return true;
        }
        return false;
    }

    // Delete event by title
    public boolean deleteEventByTitle(String title) {
        List<CalendarEventEntity> allEvents = calendarEventRepository.findAll(); 
        List<CalendarEventEntity> eventsToDelete = allEvents.stream()
                .filter(event -> event.getTitle().equals(title))
                .toList();

        if (!eventsToDelete.isEmpty()) {
            eventsToDelete.forEach(calendarEventRepository::delete);
            return true;
        }
        return false;
    }

    // Retrieve all events
    public List<CalendarEventEntity> getAllEvents() {
        return calendarEventRepository.findAll();
    }

    /**
     * Filter events by optional criteria. If start/end provided, restrict to that range first.
     */
    public List<CalendarEventEntity> filterEvents(String type, String groupName, String individual, String classPeriod,
            LocalDate start, LocalDate end) {
        List<CalendarEventEntity> source;
        if (start != null && end != null) {
            source = getEventsWithinDateRange(start, end);
        } else {
            source = getAllEvents();
        }

        return source.stream()
                .filter(e -> type == null || type.isBlank() || (e.getType() != null && e.getType().equalsIgnoreCase(type)))
                .filter(e -> groupName == null || groupName.isBlank() || (e.getGroupName() != null && e.getGroupName().equalsIgnoreCase(groupName)))
                .filter(e -> individual == null || individual.isBlank() || (e.getIndividual() != null && e.getIndividual().equalsIgnoreCase(individual)))
                .filter(e -> classPeriod == null || classPeriod.isBlank() || (e.getClassPeriod() != null && e.getClassPeriod().equalsIgnoreCase(classPeriod)))
                .toList();
    }

    public List<CalendarEventEntity> getEventsWithinDateRange(LocalDate startDate, LocalDate endDate) {
        return calendarEventRepository.findByDateBetween(startDate, endDate);
    }

    // Get event by id
    public CalendarEventEntity getEventById(int id) {
        return calendarEventRepository.findById((long) id).orElse(null);
    }

    // Turn a Slack weekly-plan message into events (see CalendarImportMsg) and save them
    public void parseSlackMessage(Map<String, String> jsonMap, LocalDate today) {
        for (CalendarEventEntity event : CalendarImportMsg.toEvents(jsonMap.get("text"), jsonMap.get("channel"), today)) {
            saveEvent(event);
        }
    }

    // ==================== BREAK-RELATED METHODS ====================

    /**
     * Create a break for a specific date.
     * Moves all regular events from that date to the next non-break day.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @param description Description of the break
     * @param moveToNextNonBreakDay Whether to move events to next non-break day
     * @return The created break CalendarEventEntity
     */
    @Transactional
    public CalendarEventEntity createBreak(LocalDate date, String name, String description, boolean moveToNextNonBreakDay) {
        // Validate inputs
        if (name == null || name.trim().isEmpty()) {
            name = "Break";
        }
        if (description == null) {
            description = "";
        }

        // Check if a break already exists for this date
        List<CalendarEventEntity> existingBreaks = calendarEventRepository.findByIsBreakAndDate(true, date);
        if (!existingBreaks.isEmpty()) {
            // Break already exists, just return it
            return existingBreaks.get(0);
        }

        // Move all regular events from this date if requested
        if (moveToNextNonBreakDay) {
            List<CalendarEventEntity> eventsOnDate = calendarEventRepository.findByDate(date);
            LocalDate nextNonBreakDay = findNextNonBreakDay(date);

            for (CalendarEventEntity event : eventsOnDate) {
                if (!event.isBreak()) { // Only move regular events, not breaks
                    event.setDate(nextNonBreakDay);
                    calendarEventRepository.save(event);
                }
            }
        }

        // Create and save the break as a CalendarEventEntity
        CalendarEventEntity breakEvent = new CalendarEventEntity(date, name, description, true);
        return calendarEventRepository.save(breakEvent);
    }

    /**
     * Create a break with name and description.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @param description Description of the break
     * @return The created break CalendarEventEntity
     */
    public CalendarEventEntity createBreak(LocalDate date, String name, String description) {
        return createBreak(date, name, description, false);
    }

    /**
     * Create a break with a simple name.
     * 
     * @param date The date of the break
     * @param name The name of the break
     * @return The created break CalendarEventEntity
     */
    public CalendarEventEntity createBreak(LocalDate date, String name) {
        return createBreak(date, name, "", false);
    }

    /**
     * Create a break with default name.
     * 
     * @param date The date of the break
     * @return The created break CalendarEventEntity
     */
    public CalendarEventEntity createBreak(LocalDate date) {
        return createBreak(date, "Break", "", false);
    }

    /**
     * Find the next non-break day starting from the given date.
     * Skips the break day itself and checks subsequent days.
     * 
     * @param date The starting date
     * @return The first date that doesn't have a break
     */
    public LocalDate findNextNonBreakDay(LocalDate date) {
        LocalDate currentDate = date.plusDays(1); // Start from the next day
        int daysChecked = 0;

        while (daysChecked < MAX_DAYS_AHEAD) {
            if (!isBreakDay(currentDate)) {
                return currentDate;
            }
            currentDate = currentDate.plusDays(1);
            daysChecked++;
        }

        // Fallback: return a date 365 days from now if no non-break day found
        return date.plusDays(MAX_DAYS_AHEAD);
    }

    /**
     * Update a break's name and description.
     * 
     * @param id The ID of the break to update
     * @param name The new name
     * @param description The new description
     * @return The updated break or null if not found
     */
    @Transactional
    public CalendarEventEntity updateBreak(Long id, String name, String description) {
        CalendarEventEntity breakEvent = getEventById(Math.toIntExact(id));
        if (breakEvent != null && breakEvent.isBreak()) {
            if (name != null && !name.trim().isEmpty()) {
                breakEvent.setName(name);
                breakEvent.setTitle(name);
            }
            if (description != null) {
                breakEvent.setDescription(description);
            }
            return calendarEventRepository.save(breakEvent);
        }
        return null;
    }

    /**
     * Delete a break by its ID.
     * Does NOT move events back (they stay on their rescheduled dates).
     * 
     * @param id The ID of the break to delete
     * @return true if the break was deleted, false if not found
     */
    @Transactional
    public boolean deleteBreakById(Long id) {
        CalendarEventEntity breakEvent = getEventById(Math.toIntExact(id));
        if (breakEvent != null && breakEvent.isBreak()) {
            calendarEventRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Delete a break by date.
     * 
     * @param date The date of the break to delete
     * @return true if a break was deleted, false if not found
     */
    @Transactional
    public boolean deleteBreakByDate(LocalDate date) {
        List<CalendarEventEntity> breaks = calendarEventRepository.findByIsBreakAndDate(true, date);
        if (!breaks.isEmpty()) {
            for (CalendarEventEntity breakEvent : breaks) {
                calendarEventRepository.delete(breakEvent);
            }
            return true;
        }
        return false;
    }

    /**
     * Get all breaks for a specific date.
     * 
     * @param date The date to check
     * @return List of breaks for that date
     */
    public List<CalendarEventEntity> getBreaksByDate(LocalDate date) {
        return calendarEventRepository.findByIsBreakAndDate(true, date);
    }

    /**
     * Get all breaks.
     * 
     * @return List of all breaks
     */
    public List<CalendarEventEntity> getAllBreaks() {
        return calendarEventRepository.findByIsBreak(true);
    }

    /**
     * Check if a date is a break day.
     * 
     * @param date The date to check
     * @return true if there is a break on this date, false otherwise
     */
    public boolean isBreakDay(LocalDate date) {
        List<CalendarEventEntity> breaks = calendarEventRepository.findByIsBreakAndDate(true, date);
        return !breaks.isEmpty();
    }

    /**
     * Get a break by ID.
     * 
     * @param id The ID of the break
     * @return The break if found, null otherwise
     */
    public CalendarEventEntity getBreakById(Long id) {
        CalendarEventEntity event = getEventById(Math.toIntExact(id));
        if (event != null && event.isBreak()) {
            return event;
        }
        return null;
    }
}
