package com.open.spring.mvc.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
public class CalendarEventTableInitializerTest {

    private static final List<String> ALL_COLUMNS = List.of(
            "id", "date", "title", "description", "type", "period",
            "class_period", "group_name", "individual", "is_break", "name");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private CalendarEventTableInitializer initializer;

    @BeforeEach
    public void setup() {
        // One shared connection, so every statement sees the same in-memory database.
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        initializer = new CalendarEventTableInitializer(jdbcTemplate);
    }

    @AfterEach
    public void teardown() {
        dataSource.destroy();
    }

    private List<String> columns() {
        return jdbcTemplate.queryForList("SELECT name FROM pragma_table_info('calendar_events')", String.class);
    }

    @Test
    public void createsTheWholeTableOnAnEmptyDatabase() {
        initializer.initialize();

        assertEquals(ALL_COLUMNS, columns());
    }

    @Test
    public void addsLaterColumnsToTheOriginalTableAndKeepsItsRows() {
        // The first calendar_events table, as recorded in schema_full.txt.
        jdbcTemplate.execute("CREATE TABLE calendar_events (id integer, date date, description varchar(255), "
                + "period varchar(255), title varchar(255), type varchar(255), primary key (id))");
        jdbcTemplate.update("INSERT INTO calendar_events (id, date, title, type) VALUES (1, '2026-10-27', 'Sprint review', 'event')");

        initializer.initialize();

        assertTrue(columns().containsAll(ALL_COLUMNS));
        assertEquals("Sprint review", jdbcTemplate.queryForObject("SELECT title FROM calendar_events WHERE id = 1", String.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT is_break FROM calendar_events WHERE id = 1", Integer.class));
    }

    @Test
    public void runningAgainChangesNothing() {
        initializer.initialize();
        jdbcTemplate.update("INSERT INTO calendar_events (date, title, is_break) VALUES ('2026-10-27', 'Kept', 0)");

        initializer.initialize();

        assertEquals(ALL_COLUMNS, columns());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM calendar_events", Integer.class));
    }
}
