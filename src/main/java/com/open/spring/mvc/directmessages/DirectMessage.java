package com.open.spring.mvc.directmessages;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One message inside a DirectMessageConversation. The sender is always the
 * authenticated Person who posted it (never a client-supplied name) -- see
 * DirectMessageService.postMessage.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class DirectMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "conversation_id")
    @JsonIgnore
    private DirectMessageConversation conversation;

    @ManyToOne
    @JoinColumn(name = "sender_id")
    @JsonIgnore
    private Person sender;

    @Column(length = 2000)
    private String body;

    /**
     * An optional image, stored as a data URL on the message itself.
     *
     * TEXT, not the varchar(2000) `body` uses: even a downscaled image is far larger
     * than that, and the page caps it at a 1280px edge before sending. Null for a
     * text-only message, which is every message sent before this column existed.
     */
    @Column(columnDefinition = "text")
    private String image;

    private Instant sentAt;

    /** Soft-delete: the row (and sentAt) are kept so history still shows a tombstone in order. */
    private boolean deleted;

    /** Set once on the first edit; stays true even if edited again. */
    private boolean edited;

    public DirectMessage(DirectMessageConversation conversation, Person sender, String body) {
        this(conversation, sender, body, null);
    }

    public DirectMessage(DirectMessageConversation conversation, Person sender, String body, String image) {
        this.conversation = conversation;
        this.sender = sender;
        this.body = body;
        this.image = image;
        this.sentAt = Instant.now();
    }
}
