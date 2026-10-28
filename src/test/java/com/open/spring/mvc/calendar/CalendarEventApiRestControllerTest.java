package com.open.spring.mvc.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** The JSON contract of /api/calendar: what the calendar page and the CS Pathway game send and read. */
public class CalendarEventApiRestControllerTest {

    @Mock
    private CalendarEventService calendarEventService;

    @InjectMocks
    private CalendarEventApiRestController controller;

    private MockMvc mvc;

    // Configured like Spring Boot's own mapper, which writes dates as "2026-10-27".
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private static CalendarEventEntity sampleEvent() {
        CalendarEventEntity event = new CalendarEventEntity(LocalDate.of(2026, 10, 27), "Sprint review",
                "Show the calendar", "event", "CSA", "P3", "UGRC", "toby");
        ReflectionTestUtils.setField(event, "id", 7L);
        return event;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(null);
        return names;
    }

    @Test
    public void eventViewHasTheSameJsonAsTheEntityItReplaces() throws Exception {
        CalendarEventEntity event = sampleEvent();

        JsonNode fromEntity = json.valueToTree(event);
        JsonNode fromView = json.valueToTree(CalendarEventApiRestController.EventView.from(event));

        assertEquals(fieldNames(fromEntity), fieldNames(fromView));
        assertEquals(fromEntity, fromView);
        assertEquals("2026-10-27", fromView.get("date").asText());
        assertEquals(false, fromView.get("break").asBoolean());
    }

    @Test
    public void listsEventsAsEventViews() throws Exception {
        when(calendarEventService.getAllEvents()).thenReturn(List.of(sampleEvent()));

        mvc.perform(get("/api/calendar/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].title").value("Sprint review"))
                .andExpect(jsonPath("$[0].date").value("2026-10-27"))
                .andExpect(jsonPath("$[0].break").value(false));
    }

    @Test
    public void addEventWithoutATitleIsRejected() throws Exception {
        mvc.perform(post("/api/calendar/add_event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"2026-10-27\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid input: 'title' cannot be null or empty."));

        verify(calendarEventService, never()).saveEvent(any());
    }

    @Test
    public void addEventFillsDefaultsAndReturnsTheSavedEvent() throws Exception {
        when(calendarEventService.saveEvent(any())).thenAnswer(call -> call.getArgument(0));

        mvc.perform(post("/api/calendar/add_event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Quiz\", \"date\": \"2026-10-28\", \"period\": \"CSA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Quiz"))
                .andExpect(jsonPath("$.type").value("event"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.classPeriod").value(""));
    }

    @Test
    public void addEventsCountsCreatedAndFailedEvents() throws Exception {
        when(calendarEventService.saveEvent(any())).thenAnswer(call -> call.getArgument(0));

        mvc.perform(post("/api/calendar/add_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\": [{\"title\": \"Quiz\", \"date\": \"2026-10-28\"}, {\"title\": \"No date\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.events[0].title").value("Quiz"))
                .andExpect(jsonPath("$.errors[0]").value("Missing title or date for event"));
    }

    @Test
    public void createBreakMovesEventsByDefaultAndAnswersWithTheBreak() throws Exception {
        CalendarEventEntity breakEvent = new CalendarEventEntity(LocalDate.of(2026, 11, 26), "Thanksgiving", "", true);
        when(calendarEventService.createBreak(any(), anyString(), anyString(), anyBoolean())).thenReturn(breakEvent);

        mvc.perform(post("/api/calendar/breaks/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"2026-11-26\", \"name\": \"Thanksgiving\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Break created successfully"))
                .andExpect(jsonPath("$.break.title").value("Thanksgiving"))
                .andExpect(jsonPath("$.break.type").value("break"))
                .andExpect(jsonPath("$.break.break").value(true));

        verify(calendarEventService).createBreak(eq(LocalDate.of(2026, 11, 26)), eq("Thanksgiving"), eq(""), eq(true));
    }

    @Test
    public void editKeepsTheFieldsTheRequestLeavesOut() throws Exception {
        // An announcement-calendar event: class periods P3 and P4, visible to everyone.
        CalendarEventEntity event = new CalendarEventEntity(LocalDate.of(2026, 10, 27), "Sprint review",
                "Show the calendar", "event", "CSA", "P3,P4", "UGRC", "");
        when(calendarEventService.getEventById(7)).thenReturn(event);
        when(calendarEventService.updateEventById(anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        // Like the calendar page, it sends no classPeriod; it leaves out type, groupName and individual too.
        mvc.perform(put("/api/calendar/edit/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newTitle\": \"Sprint review (moved)\", \"description\": \"Show the calendar\","
                                + " \"date\": \"2026-10-28\", \"priority\": \"P2\", \"period\": \"CSA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Event updated successfully."));

        verify(calendarEventService).updateEventById(7, "Sprint review (moved)", "Show the calendar",
                LocalDate.of(2026, 10, 28), "CSA", "event", "P3,P4", "UGRC", "");
    }

    @Test
    public void editOfAMissingEventIsNotFound() throws Exception {
        mvc.perform(put("/api/calendar/edit/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newTitle\": \"Quiz\", \"description\": \"Unit 3\", \"date\": \"2026-10-28\", \"period\": \"CSA\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Event with the given id not found."));

        verify(calendarEventService, never()).updateEventById(anyInt(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
