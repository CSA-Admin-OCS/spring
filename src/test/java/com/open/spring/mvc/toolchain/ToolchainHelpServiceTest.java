package com.open.spring.mvc.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.toolchain.ToolchainHelpApiRestController.HelpRequestRecord;
import com.open.spring.mvc.toolchain.ToolchainHelpService.HelpRecord;

class ToolchainHelpServiceTest {
    private static final String MESSAGE = "I need help at Git Village Hall in Toolchain Trail (Windows).";

    @Mock
    private ToolchainHelpJpaRepository repository;
    @Mock
    private PersonJpaRepository personRepository;

    private MutableClock clock;
    private ToolchainHelpService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = new MutableClock(Instant.parse("2026-10-21T16:00:00Z"));
        service = new ToolchainHelpService(repository, personRepository, clock);
        when(repository.save(any(ToolchainHelpEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findFirstByUidOrderByCreatedAtDesc("toby")).thenReturn(Optional.empty());
    }

    @Test
    void sendSavesTheStudentsRequestWithTheTimeItWasSent() {
        ToolchainHelpEntity saved = service.send("toby",
                new HelpRequestRecord("toolchain-trail", "  Git Village Hall ", " " + MESSAGE + " "));

        assertEquals("toby", saved.getUid());
        assertEquals("toolchain-trail", saved.getLevelKey());
        assertEquals("Git Village Hall", saved.getStation());
        assertEquals(MESSAGE, saved.getMessage());
        assertEquals(clock.millis(), saved.getCreatedAt());
    }

    @Test
    void sendAllowsALevelWithoutAStation() {
        ToolchainHelpEntity saved = service.send("toby", new HelpRequestRecord("toolchain-trail", " ", MESSAGE));

        assertNull(saved.getStation());
    }

    @Test
    void sendRejectsAnUnknownLevelAnEmptyMessageAndAnOverlongMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> service.send("toby", new HelpRequestRecord("not-a-level", null, MESSAGE)));
        assertThrows(IllegalArgumentException.class,
                () -> service.send("toby", new HelpRequestRecord("toolchain-trail", null, "   ")));
        assertThrows(IllegalArgumentException.class,
                () -> service.send("toby", new HelpRequestRecord("toolchain-trail", null, "x".repeat(501))));
        verify(repository, never()).save(any());
    }

    @Test
    void sendRefusesASecondRequestWithinAMinuteSoDoubleClicksDoNotFloodTheTeacher() {
        ToolchainHelpEntity previous = new ToolchainHelpEntity();
        previous.setCreatedAt(clock.millis());
        when(repository.findFirstByUidOrderByCreatedAtDesc("toby")).thenReturn(Optional.of(previous));
        clock.advance(Duration.ofSeconds(20));

        ToolchainHelpExceptionTooSoon error = assertThrows(ToolchainHelpExceptionTooSoon.class,
                () -> service.send("toby", new HelpRequestRecord("toolchain-trail", null, MESSAGE)));
        assertEquals(40, error.getRetryAfterSeconds());

        clock.advance(Duration.ofSeconds(40));
        assertEquals(MESSAGE, service.send("toby", new HelpRequestRecord("toolchain-trail", null, MESSAGE)).getMessage());
    }

    @Test
    void getRecentRequestsShowsTheStudentsNameAndTheLevelsDisplayName() {
        ToolchainHelpEntity request = new ToolchainHelpEntity();
        request.setUid("toby");
        request.setLevelKey("toolchain-trail");
        request.setStation("Git Village Hall");
        request.setMessage(MESSAGE);
        request.setCreatedAt(clock.millis());
        when(repository.findTop200ByOrderByCreatedAtDesc()).thenReturn(List.of(request));
        Person toby = new Person();
        toby.setName("Toby Leung");
        when(personRepository.findByUid("toby")).thenReturn(toby);

        HelpRecord row = service.getRecentRequests().get(0);

        assertEquals("Toby Leung", row.name());
        assertEquals("Toolchain Trail", row.levelName());
        assertEquals("Git Village Hall", row.station());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
