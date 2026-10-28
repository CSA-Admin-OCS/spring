package com.open.spring.mvc.groups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface GroupChatReadStateJpaRepository extends JpaRepository<GroupChatReadState, Long> {

    Optional<GroupChatReadState> findByGroupAndPerson(Groups group, Person person);

    /** Every group this person has read at least once; groups absent here are fully unread. */
    List<GroupChatReadState> findByPerson(Person person);
}
