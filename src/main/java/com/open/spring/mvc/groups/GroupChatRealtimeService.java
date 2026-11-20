package com.open.spring.mvc.groups;

import java.time.Instant;
import java.util.Optional;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.groups.GroupChatIdentityService.AuthenticatedIdentity;

import lombok.RequiredArgsConstructor;

/**
 * Service to handle the real-time publishing of group chat messages and presence events.
 * It uses {@link SimpMessagingTemplate} to broadcast messages to corresponding specific group topics.
 */
@Service
@RequiredArgsConstructor
public class GroupChatRealtimeService {

    public static final String GROUP_TOPIC_PREFIX = "/topic/group/";

    private final GroupChatService groupChatService;
    private final GroupsJpaRepository groupsRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupChatEvent publishMessage(Long groupId, String message, String image, AuthenticatedIdentity identity) {
        Groups group = getGroupOrThrow(groupId);

        if ((message == null || message.isBlank()) && (image == null || image.isBlank())) {
            throw new IllegalArgumentException("Message or image must be provided");
        }

        String date = Instant.now().toString();

        GroupChatMessage persisted = new GroupChatMessage(identity.getDisplayName(), identity.getUid(), message, date, image);
        groupChatService.addMessage(group.getName(), persisted);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendMessageServer")
                .groupId(groupId)
                .messageId(persisted.getId())
                .sender(persisted.getAuthor())
                .message(persisted.getMessage())
                .image(persisted.getImage())
                .date(persisted.getDate())
                .build();

        broadcastToGroup(groupId, event);
        return event;
    }

    /**
     * Returns whether the message existed (and was broadcast as edited). Throws
     * {@link SecurityException} if the authenticated requester's uid is not the author uid.
     */
    public boolean editMessage(Long groupId, String messageId, String newMessage, String requesterUid) {
        Groups group = getGroupOrThrow(groupId);
        boolean updated = groupChatService.editMessage(group.getName(), messageId, newMessage, requesterUid);
        if (!updated) {
            return false;
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("editMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .message(newMessage)
                .edited(true)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return true;
    }

    /** Throws {@link SecurityException} if the authenticated requester didn't send it. */
    public void deleteMessage(Long groupId, String messageId, String requesterUid) {
        Groups group = getGroupOrThrow(groupId);
        groupChatService.deleteMessage(group.getName(), messageId, requesterUid);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("deleteMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
    }

    /**
     * Stores a shared file and tells the group's topic about it. The frame names the file and
     * its size but carries no bytes: a big file sent to every member's socket could overflow
     * its send buffer and close it, and a member downloads a file by path when they open it.
     */
    public GroupChatEvent publishFile(Long groupId, String sender, String filename, String base64Data) {
        Groups group = getGroupOrThrow(groupId);
        String path = SharedFilePaths.clean(filename);
        if (path == null) {
            throw new IllegalArgumentException("Invalid file path");
        }

        String uploadResult = groupChatService.uploadSharedFile(group.getName(), path, base64Data, sender);
        if (uploadResult == null) {
            throw new IllegalStateException("Upload failed");
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendFileServer")
                .groupId(groupId)
                .sender(sender)
                .filename(path)
                .size(decodedLength(base64Data))
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return event;
    }

    public void publishPresence(Long groupId, String context, String sender, java.util.List<String> participants) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(context)
                .groupId(groupId)
                .sender(sender)
                .participants(participants)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishTyping(Long groupId, String sender, boolean typing) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(typing ? "typingStartServer" : "typingStopServer")
                .groupId(groupId)
                .sender(sender)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishError(Long groupId, String sender, String errorMessage) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context("errorServer")
                .groupId(groupId)
                .sender(sender)
                .error(errorMessage)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    /** How many bytes a base64 string holds, without decoding a second copy of a big file. */
    static long decodedLength(String base64Data) {
        int length = base64Data.length();
        int padding = 0;
        if (length > 0 && base64Data.charAt(length - 1) == '=') padding++;
        if (length > 1 && base64Data.charAt(length - 2) == '=') padding++;
        return Math.max(0, (long) length / 4 * 3 - padding);
    }

    private void broadcastToGroup(Long groupId, GroupChatEvent event) {
        messagingTemplate.convertAndSend(GROUP_TOPIC_PREFIX + groupId, event);
    }

    private Groups getGroupOrThrow(Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            throw new IllegalArgumentException("Group not found");
        }
        return groupOpt.get();
    }
}
