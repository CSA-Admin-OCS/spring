package com.open.spring.mvc.directmessages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface DirectMessageConversationJpaRepository extends JpaRepository<DirectMessageConversation, Long> {

    /**
     * Every conversation {@code person} is a participant of. Used both to list
     * "my conversations" and to look for an existing 1:1 thread to reuse.
     */
    List<DirectMessageConversation> findByParticipantsContaining(Person person);
}
