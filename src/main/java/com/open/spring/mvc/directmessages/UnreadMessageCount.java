package com.open.spring.mvc.directmessages;

/**
 * How many unread messages one sender has in one conversation, as seen by the
 * person the query was run for. Built by DirectMessageJpaRepository.countUnreadBySender.
 */
public record UnreadMessageCount(Long conversationId, String senderUid, String senderName, Long unreadCount) {
}
