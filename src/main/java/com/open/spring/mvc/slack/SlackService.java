package com.open.spring.mvc.slack;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Saves incoming Slack messages. Turning a weekly-plan message into calendar
 * events is CalendarImportMsg's job, in mvc/calendar.
 */
@Service
public class SlackService {

    @Autowired
    private SlackMessageRepository messageRepository;

    // ========== MESSAGE PERSISTENCE (from MessageService) ==========

    /**
     * Save a Slack message to the database.
     * Creates a new SlackMessage entity with the current timestamp and message content.
     * 
     * @param messageContent The content of the Slack message to save
     */
    public void saveMessage(String messageContent) {
        // Create a new SlackMessage entity with the current timestamp and the message content as a blob
        SlackMessage message = new SlackMessage(LocalDateTime.now(), messageContent);
        // Save to the database
        messageRepository.save(message);
    }
}
