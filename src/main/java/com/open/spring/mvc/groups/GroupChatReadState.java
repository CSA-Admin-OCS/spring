package com.open.spring.mvc.groups;

import java.time.Instant;

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
 * How far one member has read in one group's chat. A member with no row has read
 * nothing yet, so every message in that group counts as unread.
 *
 * This tracks a timestamp where {@link com.open.spring.mvc.directmessages.DirectMessageReadState}
 * tracks a last-read id, and the difference is forced by where each one's messages
 * live. Direct messages are rows with sequential ids, so "id &lt;= lastRead" orders
 * cleanly. Group chat is a JSONL file in object storage whose message ids are random
 * UUIDs: they cannot be compared, and a pointer to one would break the moment its
 * message was deleted. Every group message does carry a server-set timestamp, so
 * "sent after you last read" is the one comparison that stays correct.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "person_id"}))
@Getter
@Setter
@NoArgsConstructor
public class GroupChatReadState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "group_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Groups group;

    @ManyToOne
    @JoinColumn(name = "person_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Person person;

    private Instant lastReadAt;

    public GroupChatReadState(Groups group, Person person, Instant lastReadAt) {
        this.group = group;
        this.person = person;
        this.lastReadAt = lastReadAt;
    }
}
