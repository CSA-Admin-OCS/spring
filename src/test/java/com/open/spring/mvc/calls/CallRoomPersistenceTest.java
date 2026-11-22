package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallRoomEntity through JPA against a real SQLite file, with ddl-auto=none so the table is the one
 * CallRoomTableInitializer creates. Nothing else checks the entity's mapping against that SQL before
 * a live boot. Runs outside a test transaction so each save commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CallRoomTableInitializer.class, CallRoomPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CallRoomPersistenceTest {

    private static final Path DATABASE = createDatabaseFile();

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?journal_mode=WAL");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private static Path createDatabaseFile() {
        try {
            return Files.createTempFile("calls-test", ".db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void deleteDatabase() throws IOException {
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
    }

    /** The JPA test slice binds no settings, so the initializer gets calls switched on here. */
    @TestConfiguration
    static class Config {
        @Bean
        CallsProperties callsProperties() {
            CallsProperties properties = new CallsProperties();
            properties.setEnabled(true);
            return properties;
        }
    }

    @Autowired private CallRoomTableInitializer initializer;
    @Autowired private CallRoomJpaRepository rooms;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        jdbc.execute("DELETE FROM call_room");
    }

    private static CallRoomEntity groupRoom(String key) {
        CallRoomEntity room = CallRoomEntity.forContext(CallContextType.GROUP, key, "ada");
        room.setPurpose("standup");
        room.setTitle("Binary Beasts");
        return room;
    }

    @Test
    void aSavedRoomReadsBackByItsContext() {
        CallRoomEntity saved = rooms.save(groupRoom("12"));

        CallRoomEntity found = rooms.findByContextTypeAndContextKey(CallContextType.GROUP, "12").orElseThrow();
        assertEquals(saved.getId(), found.getId());
        assertEquals("standup", found.getPurpose());
        assertEquals("Binary Beasts", found.getTitle());
        assertEquals(CallState.IDLE, found.getState());
        assertEquals(saved.getRoomName(), found.getRoomName());
        assertEquals("ada", found.getCreatedByUid());
        assertNotNull(found.getCreatedAt());
        // Enums are stored by name, so the rows read the same to SQL and to a later Spring version.
        assertEquals("GROUP", jdbc.queryForObject("SELECT context_type FROM call_room WHERE id = ?", String.class, saved.getId()));
        assertEquals("IDLE", jdbc.queryForObject("SELECT state FROM call_room WHERE id = ?", String.class, saved.getId()));
        assertTrue(rooms.findByContextTypeAndContextKey(CallContextType.DM, "12").isEmpty());
    }

    /**
     * SQLite's dialect reports the unique key as a JpaSystemException (MySQL as a
     * DataIntegrityViolationException), so the controller's retry after a lost race catches any
     * DataAccessException, their common type.
     */
    @Test
    void aSecondRoomForTheSameContextIsRefused() {
        rooms.save(groupRoom("12"));

        assertThrows(DataAccessException.class, () -> rooms.save(groupRoom("12")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM call_room", Integer.class));
    }
}
