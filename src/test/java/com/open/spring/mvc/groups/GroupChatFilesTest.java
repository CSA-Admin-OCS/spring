package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Shared files in a group chat: the index without bytes, one file's bytes, the paths a
 * file may be stored under, the socket frame an upload sends, and who shared what when
 * several uploads land at once.
 */
class GroupChatFilesTest {

    /** S3 kept in a map, keyed the way S3FileHandler keys objects: "<group>/<filename>". */
    static class InMemoryStorage extends S3FileHandler {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String uploadFile(String base64Data, String filename, String uid) {
            objects.put(uid + "/" + filename, Base64.getDecoder().decode(base64Data));
            return filename;
        }

        @Override
        public String decodeFile(String uid, String filename) {
            byte[] bytes = objects.get(uid + "/" + filename);
            return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
        }

        @Override
        public boolean fileExists(String uid, String filename) {
            return objects.containsKey(uid + "/" + filename);
        }

        @Override
        public List<String> listFiles(String prefix) {
            return objects.keySet().stream().filter(key -> key.startsWith(prefix)).sorted().collect(Collectors.toList());
        }

        @Override
        public Map<String, Long> listFileSizes(String prefix) {
            return objects.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> (long) entry.getValue().length));
        }
    }

    private InMemoryStorage storage;
    private SimpMessagingTemplate messaging;
    private GroupChatApiController controller;

    @BeforeEach
    void setUp() {
        useStorage(new InMemoryStorage());
    }

    private void useStorage(InMemoryStorage store) {
        storage = store;
        GroupChatService chat = new GroupChatService(storage, new ObjectMapper());
        GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
        messaging = mock(SimpMessagingTemplate.class);
        GroupChatRealtimeService realtime = new GroupChatRealtimeService(chat, groups, messaging);
        GroupChatReadService reads = new GroupChatReadService(mock(GroupChatReadStateJpaRepository.class), groups, chat);
        PersonJpaRepository people = mock(PersonJpaRepository.class);
        controller = new GroupChatApiController(chat, realtime, reads, groups, people);

        Groups group = new Groups();
        group.setName("CSA");
        when(groups.findById(7L)).thenReturn(Optional.of(group));
        when(groups.findById(99L)).thenReturn(Optional.empty());

        Person ada = new Person();
        ada.setUid("ada");
        ada.setName("Ada Lovelace");
        when(people.findByUid("ada")).thenReturn(ada);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ada", null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> upload(String path, String text) {
        return controller.uploadSharedFile(7L, new GroupChatApiController.FileUploadRequest(path, base64(text)));
    }

    @Test
    void indexListsEachFileWithItsSizeAndWhoSharedItButNoBytes() {
        storage.objects.put("CSA/shared-files/", new byte[0]); // the folder placeholder
        upload("notes.txt", "hello");
        upload("project/src/Main.java", "class Main {}");
        storage.objects.put("CSA/shared-files/old.pdf", new byte[3]); // shared before attribution

        ResponseEntity<?> response = controller.getSharedFileIndex(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<GroupChatService.SharedFile> files = (List<GroupChatService.SharedFile>) response.getBody();
        assertEquals(List.of("notes.txt", "old.pdf", "project/src/Main.java"),
                files.stream().map(GroupChatService.SharedFile::path).collect(Collectors.toList()));
        assertEquals(5L, files.get(0).size());
        assertEquals("Ada Lovelace", files.get(0).uploadedBy());
        assertTrue(!files.get(0).uploadedAt().isBlank());
        assertEquals("", files.get(1).uploadedBy());
        assertEquals(13L, files.get(2).size());
    }

    @Test
    void indexOfAnUnknownGroupIsNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, controller.getSharedFileIndex(99L).getStatusCode());
    }

    @Test
    void contentAnswersWithOneFilesBytesAndItsName() {
        upload("project/src/Main.java", "class Main {}");
        upload("big.bin", "x".repeat(5000));

        ResponseEntity<?> response = controller.getSharedFileContent(7L, "project/src/Main.java");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals("class Main {}".getBytes(StandardCharsets.UTF_8), (byte[]) response.getBody());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"), disposition);
        assertTrue(disposition.contains("Main.java"), disposition);
    }

    @Test
    void contentOfAFileThatIsNotSharedIsNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, controller.getSharedFileContent(7L, "missing.txt").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getSharedFileContent(99L, "notes.txt").getStatusCode());
    }

    @Test
    void pathsThatCouldEscapeTheFolderOrConfuseItAreRefused() {
        List<String> bad = List.of("", " ", "../secrets.txt", "a/../../b", "/etc/passwd", "a//b.txt", "a\\b.txt",
                "./a.txt", "a/.", "tab\there.txt", "new\nline.txt", "x".repeat(513), "d/" + "y".repeat(256));
        for (String path : bad) {
            assertNull(SharedFilePaths.clean(path), path);
            assertEquals(HttpStatus.BAD_REQUEST, upload(path, "data").getStatusCode(), path);
            assertEquals(HttpStatus.BAD_REQUEST, controller.getSharedFileContent(7L, path).getStatusCode(), path);
        }
        assertTrue(storage.objects.isEmpty());
        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void ordinaryNamesAndFoldersAreKeptAsTyped() {
        for (String path : List.of("a.txt", "Team Turing/notes (2).md", "résumé.pdf", "src/main/java/App.java", ".gitignore")) {
            assertEquals(path, SharedFilePaths.clean(path), path);
        }
        assertEquals("App.java", SharedFilePaths.lastSegment("src/main/java/App.java"));
    }

    @Test
    void theUploadFrameNamesTheFileAndItsSizeWithoutItsBytes() {
        upload("slides/deck.pdf", "%PDF-1.7 pretend");

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/group/7"), frame.capture());
        GroupChatEvent event = (GroupChatEvent) frame.getValue();
        assertEquals("sendFileServer", event.getContext());
        assertEquals("slides/deck.pdf", event.getFilename());
        assertEquals("Ada Lovelace", event.getSender());
        assertEquals(16L, event.getSize());
        assertNull(event.getBase64Data());
    }

    @Test
    void aPathAlreadySharedIsRefusedAndTheFirstFileIsKept() {
        assertEquals(HttpStatus.OK, upload("notes.md", "Ada's notes").getStatusCode());

        ResponseEntity<?> second = upload("notes.md", "Grace's notes");

        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) second.getBody();
        assertEquals("notes.md", body.get("filename"));
        assertArrayEquals("Ada's notes".getBytes(StandardCharsets.UTF_8), storage.objects.get("CSA/shared-files/notes.md"));
        // Only the upload that was kept tells the group a file arrived.
        verify(messaging, times(1)).convertAndSend(eq("/topic/group/7"), any(Object.class));
    }

    @Test
    void twoUploadsOfOnePathAtOnceKeepOneAndRefuseTheOther() throws Exception {
        // Each write waits until both uploads are writing (or half a second passes), so two
        // uploads that both checked the path first would both write.
        CountDownLatch bothWriting = new CountDownLatch(2);
        useStorage(new InMemoryStorage() {
            @Override
            public String uploadFile(String base64Data, String filename, String uid) {
                if (filename.startsWith("shared-files/")) {
                    bothWriting.countDown();
                    try {
                        bothWriting.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.uploadFile(base64Data, filename, uid);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<ResponseEntity<?>>> results = new ArrayList<>();
        for (String text : List.of("Ada's notes", "Grace's notes")) {
            results.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("ada", null, List.of()));
                start.await();
                return upload("notes.md", text);
            }));
        }
        start.countDown();
        List<Object> statuses = new ArrayList<>();
        for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS).getStatusCode());
        pool.shutdown();

        assertEquals(1, statuses.stream().filter(HttpStatus.OK::equals).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(HttpStatus.CONFLICT::equals).count(), statuses.toString());
    }

    @Test
    void aPathBeingWrittenInOneGroupDoesNotHoldUpALookAlikeInAnother() throws Exception {
        // "a/b" sharing "c" and "a" sharing "b/c" read the same if group and path are joined.
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage slow = new InMemoryStorage() {
            @Override
            public String uploadFile(String base64Data, String filename, String uid) {
                if ("a/b".equals(uid) && filename.startsWith("shared-files/")) {
                    writing.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.uploadFile(base64Data, filename, uid);
            }
        };
        GroupChatService chat = new GroupChatService(slow, new ObjectMapper());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var first = pool.submit(() -> chat.uploadSharedFile("a/b", "c", base64("one"), "Ada"));
        assertTrue(writing.await(5, TimeUnit.SECONDS));

        assertEquals("shared-files/b/c", chat.uploadSharedFile("a", "b/c", base64("two"), "Grace"));

        release.countDown();
        assertEquals("shared-files/c", first.get(5, TimeUnit.SECONDS));
        pool.shutdown();
    }

    @Test
    void uploadsThatLandTogetherAllKeepWhoSharedThem() throws Exception {
        int count = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = "folder/file-" + i + ".txt";
            done.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("ada", null, List.of()));
                start.await();
                return upload(path, "data " + path);
            }));
        }
        start.countDown();
        for (var future : done) future.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        @SuppressWarnings("unchecked")
        List<GroupChatService.SharedFile> files =
                (List<GroupChatService.SharedFile>) controller.getSharedFileIndex(7L).getBody();
        assertEquals(count, files.size());
        assertTrue(files.stream().allMatch(file -> "Ada Lovelace".equals(file.uploadedBy())),
                files.stream().map(file -> file.path() + "=" + file.uploadedBy()).collect(Collectors.joining(", ")));
    }
}
