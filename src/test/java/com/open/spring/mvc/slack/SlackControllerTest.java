package com.open.spring.mvc.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.open.spring.mvc.calendar.CalendarEventApiRestController;

public class SlackControllerTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private CalendarEventApiRestController calendarEventController;

    @Mock
    private SlackService slackService;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private SlackMessageRepository messageRepository;

    private SlackController slackController;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        slackController = new SlackController(restTemplate);
        ReflectionTestUtils.setField(slackController, "messageRepository", messageRepository);
    }

    @Test
    public void listSlackMessagesReturnsThreadMetadata() {
        SlackMessage message = new SlackMessage(LocalDateTime.of(2026, 5, 7, 12, 0),
                "{\"ts\":\"1715083200.000100\",\"thread_ts\":\"1715083200.000100\",\"channel\":\"C123\",\"text\":\"Hello\",\"user\":\"U123\"}");
        when(messageRepository.findAll()).thenReturn(List.of(message));

        ResponseEntity<Object> response = slackController.listSlackMessages(null, null, null, null, 100);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());

        List<?> body = (List<?>) response.getBody();
        Map<?, ?> entry = (Map<?, ?>) body.get(0);
        Map<?, ?> payload = (Map<?, ?>) entry.get("payload");

        assertEquals("1715083200.000100", payload.get("thread_ts"));
        assertEquals("Hello", payload.get("text"));
    }
}