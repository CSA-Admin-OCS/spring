package com.open.spring.mvc.directmessages;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.open.spring.mvc.person.Person;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How far one participant has read in one conversation. Every message in the
 * conversation with an id at or below lastReadMessageId counts as read for that
 * person; a participant with no row has read nothing yet.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "person_id"}))
@Getter
@Setter
@NoArgsConstructor
public class DirectMessageReadState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "conversation_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private DirectMessageConversation conversation;

    @ManyToOne
    @JoinColumn(name = "person_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    private Long lastReadMessageId;

    public DirectMessageReadState(DirectMessageConversation conversation, Person person) {
        this.conversation = conversation;
        this.person = person;
    }
}
