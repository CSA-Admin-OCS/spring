package com.open.spring.mvc.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The calendar's REST API (ingress and egress). Jackson turns each JSON body into
 * one of the DTO records below; events go out as EventView records, never as
 * CalendarEventEntity. The rules are in CalendarEventService.
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarEventApiRestController {

    @Autowired
    private CalendarEventService calendarEventService;

    /** DTO for one event to add (add_event, add_bulk, and each item of add_events). Dates are YYYY-MM-DD. */
    public record EventRequest(String title, String date, String description, String type, String period,
                               String classPeriod, String groupName, String individual) {}

    /**
     * DTO for the edit/{id} body; the calendar page sends the title as newTitle.
     * type, classPeriod, groupName and individual are optional: left out, they keep the event's values.
     */
    public record EditRequest(String newTitle, String description, String date, String period, String type,
                              String classPeriod, String groupName, String individual) {}

    /** DTO for the breaks/create and breaks/{id} body; moveToNextNonBreakDay defaults to true. */
    public record BreakRequest(String date, String name, String description, Boolean moveToNextNonBreakDay) {}

    /**
     * DTO for an event in a response. Same JSON names the entity always produced,
     * including "break" (a Java name can't be break, so the component is breakDay).
     */
    public record EventView(Long id, LocalDate date, String title, String description, String type, String period,
                            String classPeriod, String groupName, String individual,
                            @JsonProperty("break") boolean breakDay, String name) {
        static EventView from(CalendarEventEntity e) {
            return new EventView(e.getId(), e.getDate(), e.getTitle(), e.getDescription(), e.getType(), e.getPeriod(),
                    e.getClassPeriod(), e.getGroupName(), e.getIndividual(), e.isBreak(), e.getName());
        }

        static List<EventView> from(List<CalendarEventEntity> events) {
            return events.stream().map(EventView::from).toList();
        }
    }

    /**
     * DTO for bulk event creation request
     */
    public record BulkEventsRequest(List<EventRequest> events) {}

    /**
     * DTO for bulk event creation response
     */
    public record BulkEventsResponse(boolean success, int created, int updated, int failed,
                                     List<EventView> events, List<String> errors) {}

    /**
     * POST /api/calendar/add_events
     * Bulk create calendar events - accepts { events: [...] } format
     * Returns detailed response with created/updated/failed counts
     */
    @PostMapping("/add_events")
    public ResponseEntity<BulkEventsResponse> addEvents(@RequestBody BulkEventsRequest request,
                                                        @AuthenticationPrincipal UserDetails userDetails) {
        List<EventView> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int created = 0, updated = 0, failed = 0;
        String currentIndividual = resolveIndividual(userDetails);

        if (request.events() == null || request.events().isEmpty()) {
            errors.add("No events provided");
            return ResponseEntity.badRequest().body(new BulkEventsResponse(false, created, updated, failed, events, errors));
        }

        for (EventRequest item : request.events()) {
            try {
                String title = item.title();
                String dateStr = item.date();
                String description = orDefault(item.description(), "");
                String type = orDefault(item.type(), "event");
                String period = item.period();
                String classPeriod = orDefault(item.classPeriod(), "");
                String groupName = orDefault(item.groupName(), "");
                String individual = orDefault(item.individual(), currentIndividual != null ? currentIndividual : "");
                if (currentIndividual != null && !currentIndividual.isBlank()) {
                    individual = currentIndividual;
                }

                if (title == null || title.trim().isEmpty() || dateStr == null || dateStr.trim().isEmpty()) {
                    failed++;
                    errors.add("Missing title or date for event");
                    continue;
                }

                LocalDate date;
                try {
                    date = LocalDate.parse(dateStr);
                } catch (Exception e) {
                    failed++;
                    errors.add("Invalid date format for: " + title);
                    continue;
                }

                // Check for duplicate (same title and date)
                CalendarEventEntity existingEvent = (individual != null && !individual.isBlank())
                    ? calendarEventService.findByTitleAndDateAndIndividual(title.trim(), date, individual.trim())
                    : calendarEventService.findByTitleAndDate(title.trim(), date);
                if (existingEvent != null) {
                    // Update existing event
                    existingEvent.setDescription(description);
                    existingEvent.setType(type);
                    existingEvent.setPeriod(period);
                    existingEvent.setClassPeriod(classPeriod);
                    existingEvent.setGroupName(groupName);
                    existingEvent.setIndividual(individual);
                    CalendarEventEntity updatedEvent = calendarEventService.saveEvent(existingEvent);
                    events.add(EventView.from(updatedEvent));
                    updated++;
                } else {
                    // Create new event
                    CalendarEventEntity event = new CalendarEventEntity(date, title.trim(), description, type, period, classPeriod, groupName, individual);
                    CalendarEventEntity savedEvent = calendarEventService.saveEvent(event);
                    events.add(EventView.from(savedEvent));
                    created++;
                }
            } catch (Exception e) {
                failed++;
                errors.add("Error processing event: " + e.getMessage());
            }
        }

        return ResponseEntity.ok(new BulkEventsResponse(failed == 0, created, updated, failed, events, errors));
    }

    /** POST /api/calendar/add - a Slack message; CalendarImportMsg reads any weekly plan in it. */
    @PostMapping("/add")
    public void addEventsFromSlackMessage(@RequestBody Map<String, String> jsonMap) {
        calendarEventService.parseSlackMessage(jsonMap, LocalDate.now());
    }

    @PostMapping("/add_bulk")
    public void addBulkEvents(@RequestBody List<EventRequest> events,
                              @AuthenticationPrincipal UserDetails userDetails) {
        String currentIndividual = resolveIndividual(userDetails);
        for (EventRequest item : events) {
            String dateStr = item.date();
            String title = item.title();
            String description = item.description();
            String type = orDefault(item.type(), "event");
            String period = item.period();
            String classPeriod = orDefault(item.classPeriod(), "");
            String groupName = orDefault(item.groupName(), "");
            String individual = orDefault(item.individual(), currentIndividual != null ? currentIndividual : "");

            LocalDate date = LocalDate.parse(dateStr);
            CalendarEventEntity event = new CalendarEventEntity(date, title, description, type, period, classPeriod, groupName, individual);
            calendarEventService.saveEvent(event);
        }
    }

    @PostMapping("/add_event")
    public ResponseEntity<Object> addEvent(@RequestBody EventRequest request,
                                           @AuthenticationPrincipal UserDetails userDetails) {
        Map<String, String> errorResponse = new HashMap<>();
        String currentIndividual = resolveIndividual(userDetails);
        try {
            String title = request.title();
            String dateStr = request.date();

            if (title == null || title.trim().isEmpty()) {
                errorResponse.put("message", "Invalid input: 'title' cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            if (dateStr == null || dateStr.trim().isEmpty()) {
                errorResponse.put("message", "Invalid input: 'date' cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                errorResponse.put("message", "Invalid date format. Use YYYY-MM-DD.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            String description = orDefault(request.description(), "");
            String type = orDefault(request.type(), "event");
            String period = request.period(); // Might be null
            String classPeriod = orDefault(request.classPeriod(), "");
            String groupName = orDefault(request.groupName(), "");
            String individual = orDefault(request.individual(), currentIndividual != null ? currentIndividual : "");

            // Validation for appointments
            if ("appointment".equals(type)) {
                // Require authentication for appointments
                if (userDetails == null) {
                    errorResponse.put("message", "Must be logged in to create appointments");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
                }

                // Check appointment limit per classPeriod per date
                if (classPeriod != null && !classPeriod.isEmpty()) {
                    long count = calendarEventService.countAppointmentsByDateAndClassPeriod(date, classPeriod);
                    if (count >= 4) {
                        errorResponse.put("message", "Maximum 4 appointments allowed per period per day");
                        return ResponseEntity.badRequest().body(errorResponse);
                    }
                }
            }

            // Check for duplicate (same title and date)
                CalendarEventEntity existingEvent = (individual != null && !individual.isBlank())
                    ? calendarEventService.findByTitleAndDateAndIndividual(title.trim(), date, individual.trim())
                    : calendarEventService.findByTitleAndDate(title.trim(), date);
            if (existingEvent != null) {
                // Update existing event instead of creating duplicate
                existingEvent.setDescription(description);
                existingEvent.setType(type);
                existingEvent.setPeriod(period);
                existingEvent.setClassPeriod(classPeriod);
                existingEvent.setGroupName(groupName);
                existingEvent.setIndividual(individual);
                CalendarEventEntity updatedEvent = calendarEventService.saveEvent(existingEvent);
                return ResponseEntity.ok(EventView.from(updatedEvent));
            }

            CalendarEventEntity event = new CalendarEventEntity(date, title, description, type, period, classPeriod, groupName, individual);
            CalendarEventEntity savedEvent = calendarEventService.saveEvent(event);

            // Return the full event object with id
            return ResponseEntity.ok(EventView.from(savedEvent));
        } catch (Exception e) {
            errorResponse.put("message", "Error adding event: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/events/{date}")
    public List<EventView> getEventsByDate(@PathVariable String date,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        LocalDate localDate = LocalDate.parse(date);
        return EventView.from(filterForCurrentUser(calendarEventService.getEventsByDate(localDate), resolveIndividual(userDetails)));
    }

    @PutMapping("/edit/{id}")
    @CrossOrigin(origins = {"http://127.0.0.1:4500","https://pages.opencodingsociety.com"}, allowCredentials = "true")
    public ResponseEntity<Object> editEvent(@PathVariable int id, @RequestBody EditRequest request,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        Map<String, String> errorResponse = new HashMap<>();
        try {
            String newTitle = request.newTitle();
            String description = request.description();
            String dateStr = request.date();
            String period = request.period();

            if (newTitle == null || newTitle.trim().isEmpty()) {
                errorResponse.put("message", "New title cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            if (description == null || description.trim().isEmpty()) {
                errorResponse.put("message", "Description cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            if (dateStr == null || dateStr.trim().isEmpty()) {
                errorResponse.put("message", "Date cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            if (period == null || period.trim().isEmpty()) {
                errorResponse.put("message", "Period cannot be null or empty.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                errorResponse.put("message", "Invalid date format. Use YYYY-MM-DD.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            CalendarEventEntity existingEvent = calendarEventService.getEventById(id);
            if (existingEvent == null) {
                errorResponse.put("message", "Event with the given id not found.");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            }

            // Fields the request leaves out keep their current values. The calendar page
            // doesn't send classPeriod, so an edit there used to erase an appointment's period
            // and the announcement calendar's class periods.
            String type = orDefault(request.type(), existingEvent.getType());
            String classPeriod = orDefault(request.classPeriod(), existingEvent.getClassPeriod());
            String groupName = orDefault(request.groupName(), existingEvent.getGroupName());
            String individual = orDefault(request.individual(), existingEvent.getIndividual());

            // Validation for appointments
            if ("appointment".equals(type)) {
                // Require authentication for appointments
                if (userDetails == null) {
                    errorResponse.put("message", "Must be logged in to create appointments");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
                }

                // Check appointment limit per classPeriod per date (exclude current event)
                if (classPeriod != null && !classPeriod.isEmpty()) {
                    // Only check limit if classPeriod or date changed
                    boolean needsLimitCheck = !classPeriod.equals(existingEvent.getClassPeriod())
                        || !date.equals(existingEvent.getDate())
                        || !"appointment".equals(existingEvent.getType());
                    
                    if (needsLimitCheck) {
                        long count = calendarEventService.countAppointmentsByDateAndClassPeriod(date, classPeriod);
                        if (count >= 4) {
                            errorResponse.put("message", "Maximum 4 appointments allowed per period per day");
                            return ResponseEntity.badRequest().body(errorResponse);
                        }
                    }
                }
            }

            boolean updated = calendarEventService.updateEventById(id, newTitle.trim(), description.trim(), date, 
                    period.trim(), type, classPeriod, groupName, individual);
            
            if (updated) {
                Map<String, String> successResponse = new HashMap<>();
                successResponse.put("message", "Event updated successfully.");
                return ResponseEntity.ok(successResponse);
            } else {
                errorResponse.put("message", "Event with the given id not found.");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            }
        } catch (Exception e) {
            errorResponse.put("message", "An error occurred while updating the event: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/events")
    public List<EventView> getAllEvents(@AuthenticationPrincipal UserDetails userDetails) {
        return EventView.from(filterForCurrentUser(calendarEventService.getAllEvents(), resolveIndividual(userDetails)));
    }

    /**
     * GET /api/calendar/events/filter
     * Optional query params: type, groupName, individual, classPeriod, start, end (YYYY-MM-DD)
     */
    @GetMapping("/events/filter")
    public List<EventView> getFilteredEvents(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String groupName,
            @RequestParam(required = false) String individual,
            @RequestParam(required = false) String classPeriod,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @AuthenticationPrincipal UserDetails userDetails) {
        String currentIndividual = resolveIndividual(userDetails);
        String effectiveIndividual = (individual != null && !individual.isBlank()) ? individual : currentIndividual;

        java.time.LocalDate startDate = null;
        java.time.LocalDate endDate = null;
        try {
            if (start != null && !start.isBlank()) startDate = java.time.LocalDate.parse(start);
            if (end != null && !end.isBlank()) endDate = java.time.LocalDate.parse(end);
        } catch (Exception e) {
            return List.of();
        }

        return EventView.from(filterForCurrentUser(
                calendarEventService.filterEvents(type, groupName, effectiveIndividual, classPeriod, startDate, endDate),
                currentIndividual));
    }

    @GetMapping("/events/range")
    public List<EventView> getEventsWithinDateRange(@RequestParam String start,
                                                       @RequestParam String end,
                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return EventView.from(filterForCurrentUser(
                calendarEventService.getEventsWithinDateRange(LocalDate.parse(start), LocalDate.parse(end)),
                resolveIndividual(userDetails)));
    }

    @GetMapping("/events/next-day")
    public List<EventView> getNextDayEvents(@AuthenticationPrincipal UserDetails userDetails) {
        return EventView.from(filterForCurrentUser(
                calendarEventService.getEventsByDate(LocalDate.now().plusDays(1)),
                resolveIndividual(userDetails)));
    }

    /**
     * GET /api/calendar/appointments/count
     * Returns the count of appointments for a specific date and classPeriod.
     * Allows frontend to check availability before submission.
     * 
     * @param date The date in YYYY-MM-DD format
     * @param classPeriod The class period (P1, P2, P3, P4, P5)
     * @return ResponseEntity containing the count
     */
    @GetMapping("/appointments/count")
    public ResponseEntity<Object> getAppointmentCount(@RequestParam String date, @RequestParam String classPeriod) {
        Map<String, Object> response = new HashMap<>();
        try {
            LocalDate parsedDate = LocalDate.parse(date);
            long count = calendarEventService.countAppointmentsByDateAndClassPeriod(parsedDate, classPeriod);
            response.put("count", count);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Invalid date format. Use YYYY-MM-DD.");
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @DeleteMapping("/delete/{id}")
    @CrossOrigin(origins = {"http://127.0.0.1:4500","https://pages.opencodingsociety.com/"}, allowCredentials = "true")
    public ResponseEntity<String> deleteEvent(@PathVariable int id) {
        System.out.println("Attempting to delete event...");
        try {
            boolean deleted = calendarEventService.deleteEventById(id);
            if (!deleted) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Event with the given id not found.");
            }
            return ResponseEntity.ok("Event deleted successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An error occurred: " + e.getMessage());
        }
    }

    @GetMapping("/events/bulk")
    public ResponseEntity<List<EventView>> bulkExtractEvents(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            List<CalendarEventEntity> events;
            if (startDate != null && endDate != null) {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end = LocalDate.parse(endDate);
                events = calendarEventService.getEventsWithinDateRange(start, end);
            } else {
                events = calendarEventService.getAllEvents();
            }
            return ResponseEntity.ok(EventView.from(filterForCurrentUser(events, resolveIndividual(userDetails))));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    /**
     * DTO for bulk delete request
     */
    public record BulkDeleteRequest(List<String> titles) {}

    /**
     * DTO for single delete by title request
     */
    public record DeleteByTitleRequest(String title) {}

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private String resolveIndividual(UserDetails userDetails) {
        return userDetails != null ? userDetails.getUsername() : null;
    }

    private List<CalendarEventEntity> filterForCurrentUser(List<CalendarEventEntity> events, String individual) {
        if (individual == null || individual.isBlank()) {
            return events;
        }

        List<CalendarEventEntity> visibleEvents = new ArrayList<>();
        for (CalendarEventEntity event : events) {
            if (event.getIndividual() == null || event.getIndividual().isBlank() || individual.equalsIgnoreCase(event.getIndividual())) {
                visibleEvents.add(event);
            }
        }
        return visibleEvents;
    }

    /**
     * DTO for bulk delete response
     */
    public record BulkDeleteResponse(boolean success, int deleted, int notFound, List<String> errors) {}

    /**
     * DELETE /api/calendar/delete_events
     * Bulk delete calendar events by titles
     * Accepts { titles: ["...", "..."] }
     */
    @DeleteMapping("/delete_events")
    public ResponseEntity<BulkDeleteResponse> deleteEvents(@RequestBody BulkDeleteRequest request) {
        List<String> errors = new ArrayList<>();
        int deletedCount = 0, notFound = 0;

        if (request.titles() == null || request.titles().isEmpty()) {
            errors.add("No titles provided");
            return ResponseEntity.badRequest().body(new BulkDeleteResponse(false, deletedCount, notFound, errors));
        }

        for (String title : request.titles()) {
            try {
                if (title == null || title.trim().isEmpty()) {
                    notFound++;
                    continue;
                }

                boolean deleted = calendarEventService.deleteEventByTitle(title.trim());
                if (deleted) {
                    deletedCount++;
                } else {
                    notFound++;
                }
            } catch (Exception e) {
                errors.add("Error deleting: " + title + " - " + e.getMessage());
            }
        }

        return ResponseEntity.ok(new BulkDeleteResponse(errors.isEmpty(), deletedCount, notFound, errors));
    }

    /**
     * DELETE /api/calendar/delete_event
     * Delete a single calendar event by title
     * Accepts { title: "..." }
     */
    @DeleteMapping("/delete_event")
    public ResponseEntity<Map<String, Object>> deleteEventByTitle(@RequestBody DeleteByTitleRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request.title() == null || request.title().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Title cannot be null or empty");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            boolean deleted = calendarEventService.deleteEventByTitle(request.title().trim());
            if (deleted) {
                response.put("success", true);
                response.put("message", "Event deleted successfully");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "Event not found with title: " + request.title());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error deleting event: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ==================== BREAK-RELATED ENDPOINTS ====================

    /**
     * POST /api/calendar/breaks/create
     * Create a new break for a specific date.
     * When a break is created, all regular events on that day can be moved to the next non-break day.
     */
    @PostMapping("/breaks/create")
    public ResponseEntity<?> createBreak(@RequestBody BreakRequest request) {
        try {
            String dateStr = request.date();
            String name = request.name();
            String description = request.description();
            boolean moveToNextNonBreakDay = request.moveToNextNonBreakDay() == null || request.moveToNextNonBreakDay();

            // Validate date
            if (dateStr == null || dateStr.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Date is required"));
            }

            // Validate name
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Break name is required"));
            }

            LocalDate date = LocalDate.parse(dateStr);

            // Handle null description
            if (description == null) {
                description = "";
            }

            CalendarEventEntity breakEvent = calendarEventService.createBreak(date, name, description, moveToNextNonBreakDay);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Break created successfully",
                "break", EventView.from(breakEvent)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid date format. Use YYYY-MM-DD"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to create break: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/calendar/breaks
     * Get all breaks.
     */
    @GetMapping("/breaks")
    public ResponseEntity<?> getAllBreaks() {
        try {
            List<CalendarEventEntity> breaks = calendarEventService.getAllBreaks();
            return ResponseEntity.ok(EventView.from(breaks));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve breaks: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/calendar/breaks/by-date?date=2026-02-01
     * Get breaks for a specific date.
     */
    @GetMapping("/breaks/by-date")
    public ResponseEntity<?> getBreaksByDate(
            @RequestParam("date") String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr);
            List<CalendarEventEntity> breaks = calendarEventService.getBreaksByDate(date);
            return ResponseEntity.ok(EventView.from(breaks));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve breaks: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/calendar/breaks/is-break-day?date=2026-02-01
     * Check if a date is a break day.
     */
    @GetMapping("/breaks/is-break-day")
    public ResponseEntity<?> isBreakDay(
            @RequestParam("date") String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr);
            boolean isBreak = calendarEventService.isBreakDay(date);
            return ResponseEntity.ok(Map.of("isBreakDay", isBreak));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to check break day: " + e.getMessage()
            ));
        }
    }

    /**
     * PUT /api/calendar/breaks/{id}
     * Edit a break's name and description.
     */
    @PutMapping("/breaks/{id}")
    public ResponseEntity<?> editBreak(@PathVariable Long id, @RequestBody BreakRequest request) {
        try {
            String name = request.name();
            String description = request.description();

            // Validate name if provided
            if (name != null && name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Break name cannot be empty"));
            }

            CalendarEventEntity updatedBreak = calendarEventService.updateBreak(id, name, description);

            if (updatedBreak == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Break updated successfully",
                "break", EventView.from(updatedBreak)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to update break: " + e.getMessage()
            ));
        }
    }

    /**
     * DELETE /api/calendar/breaks/{id}
     * Delete a break by its ID.
     */
    @DeleteMapping("/breaks/{id}")
    public ResponseEntity<?> deleteBreak(@PathVariable Long id) {
        try {
            boolean deleted = calendarEventService.deleteBreakById(id);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Break deleted successfully"
                ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to delete break: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/calendar/breaks/{id}
     * Get a break by its ID.
     */
    @GetMapping("/breaks/{id}")
    public ResponseEntity<?> getBreakById(@PathVariable Long id) {
        try {
            CalendarEventEntity breakEvent = calendarEventService.getBreakById(id);
            if (breakEvent != null) {
                return ResponseEntity.ok(EventView.from(breakEvent));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Break not found with ID: " + id
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Failed to retrieve break: " + e.getMessage()
            ));
        }
    }
}
