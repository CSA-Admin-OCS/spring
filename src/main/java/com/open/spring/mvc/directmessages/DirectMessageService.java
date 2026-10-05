package com.open.spring.mvc.directmessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for direct messages: finding/creating conversations, checking
 * who's allowed to see one, and saving + broadcasting new messages. Kept out of
 * the controller so DirectMessageApiController only has to handle HTTP concerns
 * (this mirrors GroupChatApiController -> GroupChatService/GroupChatRealtimeService).
 */
@Service
@RequiredArgsConstructor
public class DirectMessageService {

    public static final String DM_TOPIC_PREFIX = "/topic/dm/";

    private final DirectMessageConversationJpaRepository conversationRepository;
    private final DirectMessageJpaRepository messageRepository;
    private final DirectMessageReadStateJpaRepository readStateRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public record UnreadSender(String uid, String name) {
    }

    /**
     * {@code people} is every distinct person with at least one unread message for
     * the viewer (the notification badge counts people, not messages), most recent
     * first; {@code unreadByConversation} is the unread message count per conversation.
     */
    public record UnreadSummary(List<UnreadSender> people, Map<Long, Long> unreadByConversation) {
    }

    /**
     * Finds or creates the conversation between {@code requester} and
     * {@code others}. A 1:1 conversation (exactly one other person) is reused
     * the next time either person starts a chat with the other; a group of 3+
     * people always gets a brand-new conversation, since there's no single
     * "the" conversation for an arbitrary group of participants -- this
     * matches how Groups already has no dedup for its member lists.
     */
    @Transactional
    public DirectMessageConversation getOrCreateConversation(Person requester, List<Person> others) {
        if (others.size() == 1) {
            Person other = others.get(0);
            List<DirectMessageConversation> existing = conversationRepository.findByParticipantsContaining(requester);
            for (DirectMessageConversation conversation : existing) {
                List<Person> participants = conversation.getParticipants();
                if (participants.size() == 2 && containsPerson(participants, other)) {
                    return conversation;
                }
            }
        }

        List<Person> participants = new ArrayList<>();
        participants.add(requester);
        participants.addAll(others);

        return conversationRepository.save(new DirectMessageConversation(participants));
    }

    /**
     * The security boundary: only participants may read or post in a
     * conversation. Compared by id rather than Person.equals(), since equals()
     * on JPA entities can be unreliable across separately-loaded instances.
     */
    public boolean isParticipant(DirectMessageConversation conversation, Person person) {
        return containsPerson(conversation.getParticipants(), person);
    }

    @Transactional(readOnly = true)
    public List<DirectMessage> getMessages(DirectMessageConversation conversation) {
        return messageRepository.findByConversationOrderBySentAtAsc(conversation);
    }

    @Transactional
    public DirectMessageEvent postMessage(DirectMessageConversation conversation, Person sender, String body) {
        DirectMessage message = messageRepository.save(new DirectMessage(conversation, sender, body));

        DirectMessageEvent event = DirectMessageEvent.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .senderUid(sender.getUid())
                .senderName(sender.getName())
                .body(body)
                .sentAt(message.getSentAt().toString())
                .build();

        // Posting in a conversation means the sender has caught up on it.
        advanceReadMarker(conversation, sender, message.getId());

        messagingTemplate.convertAndSend(DM_TOPIC_PREFIX + conversation.getId(), event);
        return event;
    }

    @Transactional(readOnly = true)
    public UnreadSummary getUnreadSummary(Person person) {
        Map<String, UnreadSender> people = new LinkedHashMap<>();
        Map<Long, Long> unreadByConversation = new LinkedHashMap<>();
        for (UnreadMessageCount row : messageRepository.countUnreadBySender(person)) {
            people.putIfAbsent(row.senderUid(), new UnreadSender(row.senderUid(), row.senderName()));
            unreadByConversation.merge(row.conversationId(), row.unreadCount(), Long::sum);
        }
        return new UnreadSummary(List.copyOf(people.values()), unreadByConversation);
    }

    /** Marks everything currently in the conversation as read for {@code person}. */
    @Transactional
    public void markRead(DirectMessageConversation conversation, Person person) {
        messageRepository.findTopByConversationOrderByIdDesc(conversation)
                .ifPresent(latest -> advanceReadMarker(conversation, person, latest.getId()));
    }

    private void advanceReadMarker(DirectMessageConversation conversation, Person person, Long messageId) {
        DirectMessageReadState state = readStateRepository.findByConversationAndPerson(conversation, person)
                .orElseGet(() -> new DirectMessageReadState(conversation, person));
        if (state.getLastReadMessageId() == null || state.getLastReadMessageId() < messageId) {
            state.setLastReadMessageId(messageId);
            readStateRepository.save(state);
        }
    }

    private boolean containsPerson(List<Person> people, Person target) {
        return people.stream().anyMatch(person -> person.getId().equals(target.getId()));
    }
}
