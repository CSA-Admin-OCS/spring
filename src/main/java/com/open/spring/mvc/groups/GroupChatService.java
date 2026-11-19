package com.open.spring.mvc.groups;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatService {
    private static final String MESSAGES_FILE = "messages-images/messages.jsonl";
    private static final String SHARED_FILES_PREFIX = "shared-files/";
    private static final String SHARED_FILES_META = "shared-files-meta.json";

    private final S3FileHandler s3FileHandler;
    private final ObjectMapper objectMapper;

    // One lock per group for the "shared by" sidecar, which is read, changed and written
    // back on every upload: a folder shared from the page uploads several files at once,
    // and without this the last write would drop the others' attribution.
    private final Map<String, Object> fileMetaLocks = new ConcurrentHashMap<>();

    // Every [group, path] being written right now. Two uploads of one path at once would both
    // find it free and the later would replace the earlier, so the second is refused while the
    // first is held here (within this server). A pair, not "group/path": group "a/b" sharing
    // "c" and group "a" sharing "b/c" would read the same.
    private final Set<List<String>> sharesInFlight = ConcurrentHashMap.newKeySet();

    /** One shared file as the Files list shows it: no bytes, just what to show and fetch. */
    public record SharedFile(String path, long size, String uploadedBy, String uploadedAt) {
    }

    public void initGroupStorage(String groupName) {
        // Create empty messages.jsonl file
        String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
        s3FileHandler.uploadFile(emptyBase64, MESSAGES_FILE, groupName);

        // Create shared-files/ folder (S3 uses a zero-byte placeholder to represent an empty folder)
        s3FileHandler.uploadFile(emptyBase64, SHARED_FILES_PREFIX, groupName);
    }

    public void ensureGroupStorageExists(String groupName) {
        // Check if messages.jsonl exists, if not, create it
        if (!s3FileHandler.fileExists(groupName, MESSAGES_FILE)) {
            String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
            s3FileHandler.uploadFile(emptyBase64, MESSAGES_FILE, groupName);
        }

        // Check if shared-files/ folder exists, if not, create it
        if (!s3FileHandler.fileExists(groupName, SHARED_FILES_PREFIX)) {
            String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
            s3FileHandler.uploadFile(emptyBase64, SHARED_FILES_PREFIX, groupName);
        }
    }

    public List<GroupChatMessage> getMessages(String groupName) {
        String base64Data = s3FileHandler.decodeFile(groupName, MESSAGES_FILE);
        if (base64Data == null || base64Data.isBlank()) {
            return new ArrayList<>();
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid base64 data for group {} messages.", groupName, e);
            return new ArrayList<>();
        }

        String jsonl = new String(decoded, StandardCharsets.UTF_8);
        if (jsonl.isBlank()) {
            return new ArrayList<>();
        }

        List<GroupChatMessage> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(jsonl))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    GroupChatMessage msg = objectMapper.readValue(line, GroupChatMessage.class);
                    messages.add(msg);
                } catch (Exception e) {
                    log.warn("Skipping invalid message line for group {}: {}", groupName, line, e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed reading messages for group {}", groupName, e);
        }

        return messages;
    }

    public List<GroupChatMessage> addMessage(String groupName, GroupChatMessage message) {
        List<GroupChatMessage> messages = getMessages(groupName);
        messages.add(message);

        String jsonl = messages.stream()
                .map(this::toJson)
                .collect(Collectors.joining("\n"));

        String base64Data = Base64.getEncoder()
                .encodeToString(jsonl.getBytes(StandardCharsets.UTF_8));

        s3FileHandler.uploadFile(base64Data, MESSAGES_FILE, groupName);
        return messages;
    }

    /**
     * Returns whether a message with that id was found (and updated) in this group.
     * Throws {@link SecurityException} unless the authenticated requester's uid matches
     * the stored author uid. Legacy messages without an author uid cannot be edited.
     */
    public boolean editMessage(String groupName, String messageId, String newMessage, String requesterUid) {
        if (messageId == null) return false;
        List<GroupChatMessage> messages = getMessages(groupName);
        boolean found = false;
        for (GroupChatMessage m : messages) {
            if (messageId.equals(m.getId())) {
                if (requesterUid == null || !requesterUid.equals(m.getAuthor_uid())) {
                    throw new SecurityException("Not the message sender");
                }
                m.setMessage(newMessage);
                m.setEdited(true);
                found = true;
                break;
            }
        }

        if (found) {
            String jsonl = messages.stream()
                    .map(this::toJson)
                    .collect(Collectors.joining("\n"));

            String base64Data = Base64.getEncoder()
                    .encodeToString(jsonl.getBytes(StandardCharsets.UTF_8));

            s3FileHandler.uploadFile(base64Data, MESSAGES_FILE, groupName);
        }
        return found;
    }

    /** Same ownership rule as {@link #editMessage}; throws {@link SecurityException} on mismatch. */
    public void deleteMessage(String groupName, String messageId, String requesterUid) {
        if (messageId == null) return;
        List<GroupChatMessage> messages = getMessages(groupName);

        GroupChatMessage target = messages.stream()
                .filter(m -> messageId.equals(m.getId()))
                .findFirst()
                .orElse(null);
        if (target == null) return;
        if (requesterUid == null || !requesterUid.equals(target.getAuthor_uid())) {
            throw new SecurityException("Not the message sender");
        }

        boolean removed = messages.remove(target);

        if (removed) {
            String jsonl = messages.stream()
                    .map(this::toJson)
                    .collect(Collectors.joining("\n"));

            String base64Data = Base64.getEncoder()
                    .encodeToString(jsonl.getBytes(StandardCharsets.UTF_8));

            s3FileHandler.uploadFile(base64Data, MESSAGES_FILE, groupName);
        }
    }

    public List<Map<String, String>> listSharedFiles(String groupName) {
        String prefix = groupName + "/" + SHARED_FILES_PREFIX;
        List<String> keys = s3FileHandler.listFiles(prefix);

        List<String> filenames = keys.stream()
                .map(key -> key.substring(prefix.length()))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        Map<String, Map<String, String>> meta = readFileMeta(groupName);

        List<Map<String, String>> files = new ArrayList<>();
        for (String filename : filenames) {
            Map<String, String> fileEntry = new HashMap<>();
            fileEntry.put("filename", filename);
            String base64Data = s3FileHandler.decodeFile(groupName, SHARED_FILES_PREFIX + filename);
            fileEntry.put("base64Data", base64Data);
            // Empty rather than absent for a file shared before uploads were attributed,
            // so the page has one shape to render.
            Map<String, String> entry = meta.getOrDefault(filename, Map.of());
            fileEntry.put("uploadedBy", entry.getOrDefault("uploadedBy", ""));
            fileEntry.put("uploadedAt", entry.getOrDefault("uploadedAt", ""));
            files.add(fileEntry);
        }
        return files;
    }

    /**
     * Every file shared with a group, by path, with its size and who shared it, without
     * reading any file's bytes. The folder placeholder (an empty name) is left out.
     */
    public List<SharedFile> sharedFileIndex(String groupName) {
        String prefix = groupName + "/" + SHARED_FILES_PREFIX;
        Map<String, Map<String, String>> meta = readFileMeta(groupName);
        List<SharedFile> files = new ArrayList<>();
        s3FileHandler.listFileSizes(prefix).forEach((key, size) -> {
            String path = key.substring(prefix.length());
            if (path.isEmpty()) return;
            Map<String, String> entry = meta.getOrDefault(path, Map.of());
            files.add(new SharedFile(path, size == null ? 0 : size,
                    entry.getOrDefault("uploadedBy", ""), entry.getOrDefault("uploadedAt", "")));
        });
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return files;
    }

    /** One shared file's bytes, or null when the group has no file at that path. */
    public byte[] readSharedFile(String groupName, String path) {
        if (!s3FileHandler.fileExists(groupName, SHARED_FILES_PREFIX + path)) {
            return null;
        }
        String base64Data = s3FileHandler.decodeFile(groupName, SHARED_FILES_PREFIX + path);
        if (base64Data == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            log.warn("Shared file {} in group {} is not valid base64", path, groupName, e);
            return null;
        }
    }

    /** Stores a shared file, never over one already there: that throws SharedFileExistsException. */
    public String uploadSharedFile(String groupName, String filename, String base64Data, String uploadedBy) {
        List<String> key = List.of(groupName, filename);
        if (!sharesInFlight.add(key)) {
            throw new SharedFileExistsException(filename);
        }
        try {
            if (s3FileHandler.fileExists(groupName, SHARED_FILES_PREFIX + filename)) {
                throw new SharedFileExistsException(filename);
            }
            String result = s3FileHandler.uploadFile(base64Data, SHARED_FILES_PREFIX + filename, groupName);
            if (result == null) {
                return null;
            }
            recordFileMeta(groupName, filename, uploadedBy);
            return result;
        } finally {
            sharesInFlight.remove(key);
        }
    }

    // ---------------------------------------------------------------------------
    // Who shared a file.
    //
    // The object store holds bytes under a key and nothing else, so attribution lives
    // in a sidecar JSON map of filename -> { uploadedBy, uploadedAt }. It deliberately
    // sits beside shared-files/ rather than inside it: listSharedFiles() lists that
    // prefix, so a metadata file kept there would show up as a shared file itself.
    // ---------------------------------------------------------------------------

    private Map<String, Map<String, String>> readFileMeta(String groupName) {
        String base64Data = s3FileHandler.decodeFile(groupName, SHARED_FILES_META);
        if (base64Data == null || base64Data.isBlank()) {
            return new HashMap<>();
        }
        try {
            String json = new String(Base64.getDecoder().decode(base64Data), StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (Exception e) {
            // Attribution is not worth failing a file listing over.
            log.warn("Could not read shared-file metadata for group {}", groupName, e);
            return new HashMap<>();
        }
    }

    private void recordFileMeta(String groupName, String filename, String uploadedBy) {
        synchronized (fileMetaLocks.computeIfAbsent(groupName, name -> new Object())) {
            writeFileMeta(groupName, filename, uploadedBy);
        }
    }

    private void writeFileMeta(String groupName, String filename, String uploadedBy) {
        try {
            Map<String, Map<String, String>> meta = readFileMeta(groupName);
            Map<String, String> entry = new HashMap<>();
            entry.put("uploadedBy", uploadedBy == null ? "" : uploadedBy);
            entry.put("uploadedAt", Instant.now().toString());
            meta.put(filename, entry);

            String json = objectMapper.writeValueAsString(meta);
            s3FileHandler.uploadFile(
                    Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                    SHARED_FILES_META, groupName);
        } catch (Exception e) {
            log.warn("Could not record who shared {} in group {}", filename, groupName, e);
        }
    }

    public String downloadSharedFile(String groupName, String filename) {
        return s3FileHandler.decodeFile(groupName, SHARED_FILES_PREFIX + filename);
    }

    public Map<String, Object> getUserAnalytics(String personName, List<Groups> groups) {
        Map<String, Object> analytics = new HashMap<>();
        List<Map<String, Object>> groupAnalyticsList = new ArrayList<>();

        int totalMessagesSent = 0;
        int totalMessagesWithImages = 0;
        int totalSharedFiles = 0;

        for (Groups group : groups) {
            String groupName = group.getName();
            List<GroupChatMessage> messages = getMessages(groupName);

            List<GroupChatMessage> userMessages = messages.stream()
                    .filter(m -> personName.equals(m.getAuthor()))
                    .collect(Collectors.toList());

            int messagesSent = userMessages.size();
            int messagesWithImages = (int) userMessages.stream()
                    .filter(m -> m.getImage() != null && !m.getImage().isBlank())
                    .count();

            List<Map<String, String>> sharedFiles = listSharedFiles(groupName);
            int sharedFilesCount = sharedFiles.size();

            Map<String, Object> groupEntry = new HashMap<>();
            groupEntry.put("groupId", group.getId());
            groupEntry.put("groupName", groupName);
            groupEntry.put("messagesSent", messagesSent);
            groupEntry.put("messagesWithImages", messagesWithImages);
            groupEntry.put("sharedFilesCount", sharedFilesCount);
            groupAnalyticsList.add(groupEntry);

            totalMessagesSent += messagesSent;
            totalMessagesWithImages += messagesWithImages;
            totalSharedFiles += sharedFilesCount;
        }

        analytics.put("totalGroups", groups.size());
        analytics.put("totalMessagesSent", totalMessagesSent);
        analytics.put("totalMessagesWithImages", totalMessagesWithImages);
        analytics.put("totalSharedFiles", totalSharedFiles);
        analytics.put("groupAnalytics", groupAnalyticsList);

        return analytics;
    }

    private String toJson(GroupChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("Failed to serialize message: {}", message, e);
            return "{}";
        }
    }
}
