package com.open.spring.mvc.announcement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Initializes the announcement tables for databases managed with ddl-auto=none.
 *
 * It is a fallback: existing tables and rows are never changed. The columns match the
 * schema Hibernate generates for AnnouncementEntity. Because the entity's id uses
 * GenerationType.AUTO, Hibernate also needs announcement_seq with one row holding the next
 * id; that row is added only when both tables are empty, as Hibernate's own create does.
 */
@Component
@RequiredArgsConstructor
public class AnnouncementTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(AnnouncementTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateAnnouncement() {
        return args -> {
            try {
                // Unquoted lowercase names and plain types so the same statements run on SQLite and MySQL.
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS announcement ("
                        + "id bigint NOT NULL PRIMARY KEY, "
                        + "author varchar(255), "
                        + "body varchar(255), "
                        + "tags varchar(255), "
                        + "timestamp varchar(255), "
                        + "title varchar(255))");
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS announcement_seq (next_val bigint)");
                seedIdCounterIfEmpty();
            } catch (DataAccessException e) {
                logger.warn("Could not create the announcement tables: {}", e.getMessage());
            }
        };
    }

    private void seedIdCounterIfEmpty() {
        Long counterRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM announcement_seq", Long.class);
        if (counterRows != null && counterRows > 0) {
            return;
        }
        Long announcementRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM announcement", Long.class);
        if (announcementRows != null && announcementRows > 0) {
            // Guessing a start value here could hand out an id that already exists.
            logger.warn("announcement_seq is empty but announcement has {} rows; set next_val by hand.", announcementRows);
            return;
        }
        jdbcTemplate.update("INSERT INTO announcement_seq (next_val) VALUES (1)");
    }
}
