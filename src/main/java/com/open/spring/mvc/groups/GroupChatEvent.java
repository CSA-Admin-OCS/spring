package com.open.spring.mvc.groups;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupChatEvent {
    private String context;
    private Long groupId;
    private String messageId;
    private String sender;
    private String message;
    private String image;
    private String filename;
    // Left empty on sendFileServer frames: a file's bytes went to every member's socket
    // and could close it. Members fetch a file by its path when they open it.
    private String base64Data;
    // A shared file's size in bytes, on sendFileServer frames.
    private Long size;
    private String date;
    private String sessionId;
    private String error;
    private List<String> participants;
    private boolean edited;
}
