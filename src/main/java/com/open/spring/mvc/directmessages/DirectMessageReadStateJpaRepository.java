package com.open.spring.mvc.directmessages;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface DirectMessageReadStateJpaRepository extends JpaRepository<DirectMessageReadState, Long> {

    Optional<DirectMessageReadState> findByConversationAndPerson(DirectMessageConversation conversation, Person person);
}
