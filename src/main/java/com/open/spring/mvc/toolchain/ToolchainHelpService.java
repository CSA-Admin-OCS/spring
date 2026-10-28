package com.open.spring.mvc.toolchain;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.toolchain.ToolchainHelpApiRestController.HelpRequestRecord;

/**
 * Service for managing help requests sent by students via the game's "red flag" button.
 * 
 * This service handles help-request validation and persistence, enforces a cooldown
 * between requests, and retrieves recent requests for the teacher dashboard.
 */
@Service
public class ToolchainHelpService {
    /**
     * DTO combining a help request with the student's name for the teacher dashboard.
     *
     * @param name the student's display name
     * @param uid the student's unique identifier
     * @param levelName the display name of the level
     * @param station the station where help was requested
     * @param message the student's help message
     * @param createdAt when the request was created
     */
    public record HelpRecord(
            String name,
            String uid,
            String levelName,
            String station,
            String message,
            Date createdAt) {
    }

    /** The minimum time required between consecutive help requests from the same student. */
    static final Duration COOLDOWN = Duration.ofMinutes(1);
    
    /** The maximum allowed length for a help message. */
    static final int MAX_MESSAGE_LENGTH = 500;
    
    /** The maximum allowed length for a station identifier. */
    static final int MAX_STATION_LENGTH = 120;

    private static final Logger logger = LoggerFactory.getLogger(ToolchainHelpService.class);

    private final ToolchainHelpJpaRepository repository;
    private final PersonJpaRepository personRepository;
    private final Clock clock;

    /**
     * Standard constructor using the system UTC clock.
     * 
     * @param repository The repository for persisting help requests.
     * @param personRepository The repository for looking up student names.
     */
    @Autowired
    public ToolchainHelpService(ToolchainHelpJpaRepository repository, PersonJpaRepository personRepository) {
        this(repository, personRepository, Clock.systemUTC());
    }

    /**
     * Constructor for testing, allowing for a mocked clock.
     * 
     * @param repository The repository for persisting help requests.
     * @param personRepository The repository for looking up student names.
     * @param clock The clock to use for time-based operations.
     */
    ToolchainHelpService(ToolchainHelpJpaRepository repository, PersonJpaRepository personRepository, Clock clock) {
        this.repository = repository;
        this.personRepository = personRepository;
        this.clock = clock;
    }

    /**
     * Records a new help request from a student.
     * 
     * This method validates the request content, checks the cooldown period,
     * and persists the new request.
     *
     * @param uid   The unique identifier of the student.
     * @param body  The {@link HelpRequestRecord} containing the request details.
     * @return The saved {@link ToolchainHelpEntity}.
     * @throws IllegalArgumentException if the uid, level, or message is invalid.
     * @throws ToolchainHelpExceptionTooSoon if the student is still within the cooldown period.
     */
    public ToolchainHelpEntity send(String uid, HelpRequestRecord body) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Student uid is required.");
        }
        if (body == null) {
            throw new IllegalArgumentException("The help request is empty.");
        }
        ToolchainEnumLevels level = ToolchainEnumLevels.fromKey(body.levelKey())
                .orElseThrow(() -> new IllegalArgumentException("Unknown level: " + body.levelKey()));
        String message = requireText(body.message(), "message", MAX_MESSAGE_LENGTH);
        String station = body.station() == null || body.station().isBlank()
                ? null
                : requireText(body.station(), "station", MAX_STATION_LENGTH);

        long now = clock.millis();
        repository.findFirstByUidOrderByCreatedAtDesc(uid).ifPresent(previous -> {
            long waitMillis = previous.getCreatedAt() + COOLDOWN.toMillis() - now;
            if (waitMillis > 0) {
                throw new ToolchainHelpExceptionTooSoon(Math.max(1, Duration.ofMillis(waitMillis).toSeconds()));
            }
        });

        ToolchainHelpEntity request = new ToolchainHelpEntity();
        request.setId(UUID.randomUUID().toString());
        request.setUid(uid);
        request.setLevelKey(level.getKey());
        request.setStation(station);
        request.setMessage(message);
        request.setCreatedAt(now);
        ToolchainHelpEntity saved = repository.save(request);
        logger.info("CS Pathway help request from {} at {} / {}", uid, level.getKey(), station);
        return saved;
    }

    /**
     * Retrieves the most recent help requests, ordered by creation time, for the teacher's view.
     * 
     * @return A list of {@link HelpRecord} objects.
     */
    public List<HelpRecord> getRecentRequests() {
        Map<String, String> namesByUid = new HashMap<>();
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(request -> new HelpRecord(
                        namesByUid.computeIfAbsent(request.getUid(), this::displayName),
                        request.getUid(),
                        ToolchainEnumLevels.fromKey(request.getLevelKey())
                                .map(ToolchainEnumLevels::getDisplayName)
                                .orElse(request.getLevelKey()),
                        request.getStation(),
                        request.getMessage(),
                        request.getCreatedAtDate()))
                .toList();
    }

    /** Resolves a student's display name from their UID. */
    private String displayName(String uid) {
        Person person = personRepository.findByUid(uid);
        if (person == null || person.getName() == null || person.getName().isBlank()) {
            return uid;
        }
        return person.getName();
    }

    /** Validates that a text field is not empty and does not exceed the maximum length. */
    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The " + field + " is empty.");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("The " + field + " is longer than " + maxLength + " characters.");
        }
        return trimmed;
    }
}
