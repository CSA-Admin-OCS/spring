package com.open.spring.mvc.mentor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.Getter;
import lombok.Setter;

/**
 * Private mentor <-> admin messaging (the dashboard's Messages tab).
 *
 *   GET/POST /api/mentor-messages/mine             a mentor's own thread with the admins
 *   GET      /api/mentor-messages/threads          admin: one summary row per mentor
 *   GET/POST /api/mentor-messages/threads/{uid}    admin: read / reply in a mentor's thread
 *
 * SecurityConfig limits the path to ROLE_MENTOR / ROLE_ADMIN; the per-endpoint role
 * checks below decide which side may call what. The sender is always taken from the
 * signed-in account, never from the request body.
 */
@RestController
@RequestMapping("/api/mentor-messages")
public class MentorAdminMessageApiController {
    private static final Logger logger = LoggerFactory.getLogger(MentorAdminMessageApiController.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;

    @Autowired
    private MentorAdminMessageJpaRepository messageRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Getter
    @Setter
    public static class MessageDto {
        private String message;
    }

    @GetMapping("/mine")
    public ResponseEntity<Object> getMyThread(Authentication authentication) {
        if (!hasAuthority(authentication, "ROLE_MENTOR")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return new ResponseEntity<>(messageRepository.findByMentorUidOrderByIdAsc(authentication.getName()), HttpStatus.OK);
    }

    @PostMapping("/mine")
    public ResponseEntity<Object> postToMyThread(@RequestBody MessageDto body, Authentication authentication) {
        if (!hasAuthority(authentication, "ROLE_MENTOR")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return save(authentication.getName(), body, authentication, false);
    }

    @GetMapping("/threads")
    public ResponseEntity<Object> listThreads(Authentication authentication) {
        if (!hasAuthority(authentication, "ROLE_ADMIN")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        Map<String, List<MentorAdminMessage>> byMentor = messageRepository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.groupingBy(MentorAdminMessage::getMentorUid));

        // Every current mentor gets a row (so an admin can start a conversation), newest
        // activity first; mentors with no messages yet sort last, by name.
        List<Map<String, Object>> threads = new ArrayList<>();
        for (Person mentor : personRepository.findPeopleWithRole("ROLE_MENTOR")) {
            List<MentorAdminMessage> messages = byMentor.getOrDefault(mentor.getUid(), List.of());
            MentorAdminMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mentorUid", mentor.getUid());
            row.put("mentorName", mentor.getName());
            row.put("messageCount", messages.size());
            row.put("lastMessage", last == null ? null : last.getMessage());
            row.put("lastAt", last == null ? null : last.getCreatedAt());
            threads.add(row);
        }
        threads.sort(Comparator
                .comparing((Map<String, Object> row) -> (String) row.get("lastAt"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> String.valueOf(row.get("mentorName"))));
        return new ResponseEntity<>(threads, HttpStatus.OK);
    }

    @GetMapping("/threads/{mentorUid}")
    public ResponseEntity<Object> getThread(@PathVariable String mentorUid, Authentication authentication) {
        if (!hasAuthority(authentication, "ROLE_ADMIN")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        if (!isMentor(mentorUid)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(messageRepository.findByMentorUidOrderByIdAsc(mentorUid), HttpStatus.OK);
    }

    @PostMapping("/threads/{mentorUid}")
    public ResponseEntity<Object> replyInThread(@PathVariable String mentorUid, @RequestBody MessageDto body,
                                                Authentication authentication) {
        if (!hasAuthority(authentication, "ROLE_ADMIN")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        if (!isMentor(mentorUid)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return save(mentorUid, body, authentication, true);
    }

    private ResponseEntity<Object> save(String mentorUid, MessageDto body, Authentication authentication, boolean fromAdmin) {
        String text = body == null || body.getMessage() == null ? "" : body.getMessage().trim();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH) {
            return new ResponseEntity<>(Map.of("error", "message must be 1-" + MAX_MESSAGE_LENGTH + " characters"), HttpStatus.BAD_REQUEST);
        }
        Person sender = personRepository.findByUid(authentication.getName());
        if (sender == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        MentorAdminMessage message = new MentorAdminMessage();
        message.setMentorUid(mentorUid);
        message.setSenderUid(sender.getUid());
        message.setSenderName(sender.getName());
        message.setFromAdmin(fromAdmin);
        message.setMessage(text);
        message.setCreatedAt(Instant.now().toString());
        messageRepository.save(message);
        logger.info("AUDIT mentor_admin_message thread={} sender={} fromAdmin={}", mentorUid, sender.getUid(), fromAdmin);

        return new ResponseEntity<>(messageRepository.findByMentorUidOrderByIdAsc(mentorUid), HttpStatus.OK);
    }

    private boolean isMentor(String uid) {
        Person person = personRepository.findByUid(uid);
        return person != null && person.getRoles().stream().anyMatch(role -> "ROLE_MENTOR".equals(role.getName()));
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
