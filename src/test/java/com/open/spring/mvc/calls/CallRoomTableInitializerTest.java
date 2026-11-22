package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
class CallRoomTableInitializerTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private CallRoomTableInitializer initializer(boolean callsEnabled) {
        CallsProperties properties = new CallsProperties();
        properties.setEnabled(callsEnabled);
        return new CallRoomTableInitializer(jdbc, properties);
    }

    private void insert(String contextType, String contextKey, String roomName) {
        jdbc.update("INSERT INTO call_room (context_type, context_key, purpose, room_name, state) VALUES (?, ?, 'meeting', ?, 'IDLE')",
                contextType, contextKey, roomName);
    }

    private boolean tableExists() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'call_room'",
                Integer.class) == 1;
    }

    @Test
    void createsTheTableWithEveryEntityColumn() {
        initializer(true).initialize();

        assertEquals(List.of("id", "context_type", "context_key", "purpose", "room_name", "title", "state",
                        "created_by_uid", "created_at", "started_at", "ended_at"),
                jdbc.queryForList("SELECT name FROM pragma_table_info('call_room')", String.class));
    }

    @Test
    void runningAgainKeepsExistingRooms() {
        initializer(true).initialize();
        insert("GROUP", "12", "room-a");

        initializer(true).initialize();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM call_room", Integer.class));
    }

    @Test
    void oneRoomPerContext() {
        initializer(true).initialize();
        insert("GROUP", "12", "room-a");

        assertThrows(DataAccessException.class, () -> insert("GROUP", "12", "room-b"));
        insert("DM", "12", "room-c");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM call_room", Integer.class));
    }

    @Test
    void roomNamesAreUnique() {
        initializer(true).initialize();
        insert("GROUP", "12", "room-a");

        assertThrows(DataAccessException.class, () -> insert("GROUP", "13", "room-a"));
    }

    @Test
    void theBootRunnerCreatesTheTableOnlyWhenCallsAreOn() throws Exception {
        initializer(false).createCallRoomTable().run(new DefaultApplicationArguments());
        assertFalse(tableExists());

        initializer(true).createCallRoomTable().run(new DefaultApplicationArguments());
        assertTrue(tableExists());
    }
}
