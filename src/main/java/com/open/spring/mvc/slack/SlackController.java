package com.open.spring.mvc.slack;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.calendar.CalendarEventApiRestController;

@RestController
public class SlackController {

    /* 
    My slack bot's API key :(
    I would never actually leak my api key if it were for something more serious like a paid service or
    something more linked to account security, but to save me and the rest of the class from having to
    paste the key in their .envs manually I put my key here publicly
    */
    private String slackToken = "xoxp-7892664186276-7887305704597-7924387129461-e2333e0f3c20a3ddb2ba833ec37f4e52";
    
    // Rest template for API handling
    @Autowired
    private CalendarEventApiRestController calendarEventController;
    
    @Autowired
    private final RestTemplate restTemplate;

    // UPDATED: Now using consolidated SlackService instead of MessageService
    @Autowired
    private SlackService slackService;

    @Autowired
    private EmailNotificationService emailNotificationService;

    @Autowired
    private SlackMessageRepository messageRepository;

    // Constructor
    public SlackController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Main message receiver function
    @PostMapping("/slack/events")
    public ResponseEntity<String> handleSlackEvent(@RequestBody SlackEvent payload) {
        if (payload.getChallenge() != null) {
            return ResponseEntity.ok(payload.getChallenge());
        }
    
        try {
            SlackEvent.Event messageEvent = payload.getEvent();
            String eventType = messageEvent.getType();
    
            // Distinguishing messages from other events
            if ("message".equals(eventType)) {
                ObjectMapper objectMapper = new ObjectMapper();
                String messageContent = objectMapper.writeValueAsString(messageEvent);
    
                // Mapping the message's content to key-value pairs
                Map<String, String> messageData = objectMapper.readValue(messageContent, Map.class);
    
                // UPDATED: Using slackService instead of messageService
                // Saving message to DB
                slackService.saveMessage(messageContent);
                System.out.println("Message saved to database: " + messageContent);

                // Notify configured email recipient(s) without creating new systems
                emailNotificationService.notifyOnSlackMessage(messageData);

                // Direct call to the CalendarEventApiRestController method
                calendarEventController.addEventsFromSlackMessage(messageData);
                System.out.println("Message processed by CalendarEventApiRestController");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    
    
        return ResponseEntity.ok("OK");
    }

    /**
     * GET /slack/messages
     * Optional query params: contains (text), channel, start (ISO datetime), end (ISO datetime), limit
     */
    @GetMapping("/slack/messages")
    public ResponseEntity<Object> listSlackMessages(
            @RequestParam(required = false) String contains,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        try {
            java.time.LocalDateTime startDt = null;
            java.time.LocalDateTime endDt = null;
            if (start != null && !start.isBlank()) startDt = java.time.LocalDateTime.parse(start);
            if (end != null && !end.isBlank()) endDt = java.time.LocalDateTime.parse(end);

            java.util.List<SlackMessage> all = messageRepository.findAll();
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            for (SlackMessage m : all) {
                if (out.size() >= limit) break;
                String blob = m.getMessageBlob();
                java.util.Map<?,?> map = mapper.readValue(blob, java.util.Map.class);

                // timestamp filtering
                if (startDt != null || endDt != null) {
                    java.time.LocalDateTime ts = m.getTimestamp();
                    if (startDt != null && ts.isBefore(startDt)) continue;
                    if (endDt != null && ts.isAfter(endDt)) continue;
                }

                // channel filter
                if (channel != null && !channel.isBlank()) {
                    Object ch = map.get("channel");
                    if (ch == null || !channel.equals(String.valueOf(ch))) continue;
                }

                // contains filter (search in text field)
                if (contains != null && !contains.isBlank()) {
                    Object text = map.get("text");
                    if (text == null || !String.valueOf(text).toLowerCase().contains(contains.toLowerCase())) continue;
                }

                java.util.Map<String,Object> entry = new java.util.HashMap<>();
                entry.put("timestamp", m.getTimestamp());
                entry.put("payload", map);
                out.add(entry);
            }

            return ResponseEntity.ok(out);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("error", e.getMessage()));
        }
    }
}