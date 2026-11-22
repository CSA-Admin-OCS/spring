package com.open.spring.mvc.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the attendance initializer against a real in-memory SQLite database. */
class CallAttendanceTableInitializerTest {

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

    private CallAttendanceTableInitializer initializer(boolean callsEnabled) {
        CallsProperties properties = new CallsProperties();
        properties.setEnabled(callsEnabled);
        return new CallAttendanceTableInitializer(jdbc, properties);
    }

    private boolean tableExists() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'call_attendance'",
                Integer.class) == 1;
    }

    @Test
    void createsTheTableWithEveryEntityColumn() {
        initializer(true).initialize();

        assertEquals(List.of("id", "call_room_id", "identity", "uid", "roles", "joined_at", "left_at"),
                jdbc.queryForList("SELECT name FROM pragma_table_info('call_attendance')", String.class));
    }

    @Test
    void runningAgainKeepsExistingRows() {
        initializer(true).initialize();
        jdbc.update("INSERT INTO call_attendance (call_room_id, identity, joined_at) VALUES (1, 'id-ada', CURRENT_TIMESTAMP)");

        initializer(true).initialize();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM call_attendance", Integer.class));
    }

    @Test
    void theBootRunnerCreatesTheTableOnlyWhenCallsAreOn() throws Exception {
        initializer(false).createCallAttendanceTable().run(new DefaultApplicationArguments());
        assertFalse(tableExists());

        initializer(true).createCallAttendanceTable().run(new DefaultApplicationArguments());
        assertTrue(tableExists());
    }
}
