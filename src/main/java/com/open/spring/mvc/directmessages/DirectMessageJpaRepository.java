package com.open.spring.mvc.directmessages;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.open.spring.mvc.person.Person;

public interface DirectMessageJpaRepository extends JpaRepository<DirectMessage, Long> {

    List<DirectMessage> findByConversationOrderBySentAtAsc(DirectMessageConversation conversation);

    Optional<DirectMessage> findTopByConversationOrderByIdDesc(DirectMessageConversation conversation);

    Optional<DirectMessage> findByIdAndConversation(Long id, DirectMessageConversation conversation);

    /**
     * One row per (conversation, sender) that has messages {@code person} hasn't read: messages
     * from someone else in the person's conversations, newer than the person's read marker for
     * that conversation (all of them if they never opened it). Most recent sender first.
     */
    @Query("""
            SELECT new com.open.spring.mvc.directmessages.UnreadMessageCount(c.id, s.uid, s.name, COUNT(m))
            FROM DirectMessage m JOIN m.conversation c JOIN m.sender s JOIN c.participants p
            WHERE p = :person AND s <> :person AND m.deleted = false
              AND m.id > COALESCE((SELECT r.lastReadMessageId FROM DirectMessageReadState r
                                   WHERE r.conversation = c AND r.person = :person), 0L)
            GROUP BY c.id, s.uid, s.name
            ORDER BY MAX(m.id) DESC
            """)
    List<UnreadMessageCount> countUnreadBySender(@Param("person") Person person);
}
