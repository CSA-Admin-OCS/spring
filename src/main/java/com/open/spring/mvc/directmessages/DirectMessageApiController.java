package com.open.spring.mvc.directmessages;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * REST API for direct messages between 2 or more people. Every endpoint below
 * resolves "who am I" from the JWT-authenticated principal (never a
 * client-supplied name), and every conversation-scoped endpoint checks that
 * the caller is actually a participant before returning anything -- that
 * check is the whole point of this being separate from Groups' chat.
 */
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DirectMessageApiController {

    private final DirectMessageService directMessageService;
    private final DirectMessageConversationJpaRepository conversationRepository;
    private final PersonJpaRepository personRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartConversationRequest {
        private List<Long> otherPersonIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        private String body;
    }

    @GetMapping("/conversations")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getConversations(@AuthenticationPrincipal UserDetails userDetails) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        List<Map<String, Object>> conversations = conversationRepository.findByParticipantsContaining(person)
                .stream()
                .map(conversation -> conversationSummary(conversation, person))
                .toList();

        return new ResponseEntity<>(conversations, HttpStatus.OK);
    }

    @PostMapping("/conversations")
    @Transactional
    public ResponseEntity<Object> startConversation(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody StartConversationRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        if (request == null || request.getOtherPersonIds() == null || request.getOtherPersonIds().isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "otherPersonIds is required"), HttpStatus.BAD_REQUEST);
        }

        List<Person> others = request.getOtherPersonIds().stream()
                .map(id -> personRepository.findById(id).orElse(null))
                .filter(other -> other != null && !other.getId().equals(person.getId()))
                .toList();

        if (others.isEmpty()) {
            return new ResponseEntity<>(Map.of("error", "No valid recipients found"), HttpStatus.BAD_REQUEST);
        }

        DirectMessageConversation conversation = directMessageService.getOrCreateConversation(person, others);
        return new ResponseEntity<>(conversationSummary(conversation, person), HttpStatus.OK);
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getMessages(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        List<Map<String, Object>> messages = directMessageService.getMessages(conversation).stream()
                .map(this::messageSummary)
                .toList();

        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @PostMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<Object> postMessage(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable("id") Long id,
            @RequestBody SendMessageRequest request) {
        Person person = currentPerson(userDetails);
        if (person == null) {
            return new ResponseEntity<>(Map.of("error", "User not authenticated"), HttpStatus.UNAUTHORIZED);
        }

        Optional<DirectMessageConversation> conversationOpt = conversationRepository.findById(id);
        if (conversationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        DirectMessageConversation conversation = conversationOpt.get();
        if (!directMessageService.isParticipant(conversation, person)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return new ResponseEntity<>(Map.of("error", "body is required"), HttpStatus.BAD_REQUEST);
        }

        DirectMessageEvent event = directMessageService.postMessage(conversation, person, request.getBody().trim());
        return new ResponseEntity<>(event, HttpStatus.OK);
    }

    private Person currentPerson(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return personRepository.findByUid(userDetails.getUsername());
    }

    private Map<String, Object> conversationSummary(DirectMessageConversation conversation, Person self) {
        List<Map<String, Object>> otherParticipants = conversation.getParticipants().stream()
                .filter(participant -> !participant.getId().equals(self.getId()))
                .map(participant -> Map.<String, Object>of(
                        "id", participant.getId(),
                        "uid", participant.getUid(),
                        "name", participant.getName()))
                .toList();

        return Map.of(
                "id", conversation.getId(),
                "participants", otherParticipants);
    }

    private Map<String, Object> messageSummary(DirectMessage message) {
        Person sender = message.getSender();
        return Map.of(
                "id", message.getId(),
                "senderUid", sender.getUid(),
                "senderName", sender.getName(),
                "body", message.getBody(),
                "sentAt", message.getSentAt().toString());
    }
}
