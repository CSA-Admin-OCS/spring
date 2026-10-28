package com.open.spring.mvc.calendar;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Initializes the calendar_events table for databases managed with ddl-auto=none.
 * It is the fallback for a database that was not built by scripts/db_init.py:
 * it creates the table when it is missing and adds the columns that older copies
 * of the table lack (appointments and breaks came after the first version).
 * Column names are the ones Hibernate maps CalendarEventEntity's fields to.
 */
@Component
@RequiredArgsConstructor
public class CalendarEventTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(CalendarEventTableInitializer.class);

    /** Columns added after the first calendar_events table (see schema_full.txt), with their types. */
    static final Map<String, String> LATER_COLUMNS = new LinkedHashMap<>();
    static {
        LATER_COLUMNS.put("class_period", "varchar(255)");
        LATER_COLUMNS.put("group_name", "varchar(255)");
        LATER_COLUMNS.put("individual", "varchar(255)");
        // NOT NULL needs a default so existing rows can take the new column.
        LATER_COLUMNS.put("is_break", "boolean NOT NULL DEFAULT FALSE");
        LATER_COLUMNS.put("name", "varchar(255)");
    }

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateCalendarEvents() {
        return args -> initialize();
    }

    void initialize() {
        Dialect dialect;
        try {
            dialect = jdbcTemplate.execute((ConnectionCallback<Dialect>) connection -> new Dialect(
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getIdentifierQuoteString()));
        } catch (DataAccessException e) {
            logger.warn("Could not read the database type for calendar_events: {}", e.getMessage());
            return;
        }

        try {
            // Identifiers are quoted (double quotes on SQLite, backticks on MySQL) because date, type
            // and name are SQL keywords; Hibernate quotes them too (globally_quoted_identifiers).
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + dialect.quote("calendar_events") + " ("
                    + dialect.idColumn() + ", "
                    + dialect.quote("date") + " date, "
                    + dialect.quote("title") + " varchar(255), "
                    + dialect.quote("description") + " varchar(255), "
                    + dialect.quote("type") + " varchar(255), "
                    + dialect.quote("period") + " varchar(255), "
                    + dialect.quote("class_period") + " varchar(255), "
                    + dialect.quote("group_name") + " varchar(255), "
                    + dialect.quote("individual") + " varchar(255), "
                    + dialect.quote("is_break") + " boolean NOT NULL DEFAULT FALSE, "
                    + dialect.quote("name") + " varchar(255))");
        } catch (DataAccessException e) {
            logger.warn("Could not create calendar_events table: {}", e.getMessage());
        }

        for (Map.Entry<String, String> column : LATER_COLUMNS.entrySet()) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + dialect.quote("calendar_events")
                        + " ADD COLUMN " + dialect.quote(column.getKey()) + " " + column.getValue());
                logger.info("Added {} column to calendar_events", column.getKey());
            } catch (DataAccessException e) {
                // Already there.
            }
        }
    }

    /** The two things that differ between SQLite and MySQL here: identifier quotes and the auto-increment id. */
    private record Dialect(String productName, String quoteString) {
        boolean mySql() {
            String product = productName == null ? "" : productName.toLowerCase();
            return product.contains("mysql") || product.contains("mariadb");
        }

        String quote(String identifier) {
            if (quoteString == null || quoteString.isBlank()) return identifier;
            return quoteString + identifier + quoteString;
        }

        String idColumn() {
            return mySql()
                    ? quote("id") + " BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                    : quote("id") + " INTEGER PRIMARY KEY AUTOINCREMENT";
        }
    }
}
