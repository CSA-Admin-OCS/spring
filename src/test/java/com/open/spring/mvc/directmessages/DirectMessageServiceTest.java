package com.open.spring.mvc.directmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;

class DirectMessageServiceTest {

    private DirectMessageService service;
    private List<DirectMessageConversation> saved;
    private Person alice;
    private Person bob;
    private Person carol;

    @BeforeEach
    void setUp() {
        alice = person(1L, "alice");
        bob = person(2L, "bob");
        carol = person(3L, "carol");
        saved = new ArrayList<>();

        DirectMessageConversationJpaRepository conversationRepository = repositoryStub(
            DirectMessageConversationJpaRepository.class,
            (methodName, arguments) -> switch (methodName) {
                case "findByParticipantsContaining" -> {
                    Person target = (Person) arguments[0];
                    yield saved.stream()
                        .filter(conversation -> conversation.getParticipants().stream()
                            .anyMatch(p -> p.getId().equals(target.getId())))
                        .toList();
                }
                case "save" -> {
                    DirectMessageConversation conversation = (DirectMessageConversation) arguments[0];
                    if (conversation.getId() == null) {
                        ReflectionTestUtils.setField(conversation, "id", (long) (saved.size() + 1));
                    }
                    saved.add(conversation);
                    yield conversation;
                }
                default -> throw new UnsupportedOperationException(methodName);
            }
        );

        DirectMessageJpaRepository messageRepository = repositoryStub(
            DirectMessageJpaRepository.class,
            (methodName, arguments) -> {
                throw new UnsupportedOperationException(methodName);
            }
        );

        // A real SimpMessagingTemplate over a no-op channel: postMessage isn't
        // exercised by these tests, but the service still needs one to construct.
        service = new DirectMessageService(conversationRepository, messageRepository,
                new SimpMessagingTemplate((message, timeout) -> true));
    }

    @Test
    void reusesTheSame1to1ConversationRegardlessOfWhoStartsIt() {
        DirectMessageConversation first = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation second = service.getOrCreateConversation(bob, List.of(alice));

        assertSame(first, second);
        assertEquals(1, saved.size());
    }

    @Test
    void startingAGroupChatAlwaysCreatesANewConversation() {
        DirectMessageConversation oneOnOne = service.getOrCreateConversation(alice, List.of(bob));
        DirectMessageConversation group = service.getOrCreateConversation(alice, List.of(bob, carol));

        assertNotEquals(oneOnOne.getId(), group.getId());
        assertEquals(2, saved.size());
    }

    @Test
    void onlyParticipantsPassTheMembershipCheck() {
        DirectMessageConversation conversation = service.getOrCreateConversation(alice, List.of(bob));

        assertTrue(service.isParticipant(conversation, alice));
        assertTrue(service.isParticipant(conversation, bob));
        assertFalse(service.isParticipant(conversation, carol));
    }

    private Person person(Long id, String uid) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        return person;
    }

    @SuppressWarnings("unchecked")
    private <T> T repositoryStub(Class<T> repositoryType, RepositoryCall call) {
        return (T) Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (proxy, method, arguments) -> call.invoke(method.getName(), arguments)
        );
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String methodName, Object[] arguments);
    }
}
