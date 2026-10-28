package com.open.spring.mvc.directmessages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The payload broadcast to /topic/dm/{conversationId} whenever a new message
 * is posted. Plain data holder, mirrors GroupChatEvent's role for group chat.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectMessageEvent {
    private Long id;
    private Long conversationId;
    private String type;      // "message" for new posts, "edited" for edits
    private String senderUid;
    private String senderName;
    private String senderPfp;
    private String body;
    /** Data URL for a message that carries an image; null otherwise. */
    private String image;
    private String sentAt;
    private boolean deleted;
    private boolean edited;
}
