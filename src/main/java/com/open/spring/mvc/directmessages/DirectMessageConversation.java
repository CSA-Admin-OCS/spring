package com.open.spring.mvc.directmessages;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A private conversation between 2 or more Person participants. Modeled on
 * Groups.groupMembers (same @ManyToMany + @JoinTable shape), but kept as its
 * own entity so direct messages get their own, always-enforced participant
 * check instead of inheriting Groups' membership checks.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class DirectMessageConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToMany
    @JoinTable(
        name = "direct_message_participants",
        joinColumns = @JoinColumn(name = "conversation_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id")
    )
    @JsonIgnore
    private List<Person> participants = new ArrayList<>();

    public DirectMessageConversation(List<Person> participants) {
        this.participants = participants;
    }
}
