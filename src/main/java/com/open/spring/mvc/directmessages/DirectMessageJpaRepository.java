package com.open.spring.mvc.directmessages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessageJpaRepository extends JpaRepository<DirectMessage, Long> {

    List<DirectMessage> findByConversationOrderBySentAtAsc(DirectMessageConversation conversation);
}
