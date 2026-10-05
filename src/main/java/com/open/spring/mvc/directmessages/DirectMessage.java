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

    private Instant sentAt;

    public DirectMessage(DirectMessageConversation conversation, Person sender, String body) {
        this.conversation = conversation;
        this.sender = sender;
        this.body = body;
        this.sentAt = Instant.now();
    }
}
