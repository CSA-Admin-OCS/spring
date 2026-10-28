package com.open.spring.mvc.groups;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatMessage {
    private String id;
    private String author;
    private String author_uid;
    private String message;
    private String date;
    private String image;
    /** Set once on the first edit; stays true even if edited again. */
    private boolean edited;

    public GroupChatMessage(String author, String author_uid, String message, String date, String image) {
        this.id = UUID.randomUUID().toString();
        this.author = author;
        this.author_uid = author_uid;
        this.message = message;
        this.date = date;
        this.image = image;
    }
}
