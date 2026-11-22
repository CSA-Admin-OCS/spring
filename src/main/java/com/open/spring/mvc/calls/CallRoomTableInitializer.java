package com.open.spring.mvc.calls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates call_room, the table behind {@link CallRoomEntity}, when calls are on. Hibernate runs with
 * ddl-auto=none, so a new entity's table reaches a live database only through idempotent SQL like
 * this, run on every boot. The statement is unquoted so it runs on SQLite and on MySQL. While
 * calls.enabled is false nothing is created, so turning calls off leaves the database as it was.
 */
@Component
public class CallRoomTableInitializer {

    static final String TABLE = "call_room";

    private static final Logger logger = LoggerFactory.getLogger(CallRoomTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final CallsProperties properties;

    public CallRoomTableInitializer(JdbcTemplate jdbcTemplate, CallsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Bean
    public ApplicationRunner createCallRoomTable() {
        return args -> {
            if (properties.isEnabled()) {
                initialize();
            }
        };
    }

    void initialize() {
        boolean mySql = isMySql();
        String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "context_type VARCHAR(16) NOT NULL, "
                    + "context_key VARCHAR(64) NOT NULL, "
                    + "purpose VARCHAR(32) NOT NULL, "
                    + "room_name VARCHAR(64) NOT NULL, "
                    + "title VARCHAR(255), "
                    + "state VARCHAR(16) NOT NULL, "
                    + "created_by_uid VARCHAR(255), "
                    + "created_at " + timestamp + ", "
                    + "started_at " + timestamp + ", "
                    + "ended_at " + timestamp + ", "
                    + "CONSTRAINT uk_call_room_context UNIQUE (context_type, context_key), "
                    + "CONSTRAINT uk_call_room_name UNIQUE (room_name))");
        } catch (DataAccessException e) {
            // The rest of Spring keeps running; /api/calls answers 503 CALLS_UNAVAILABLE until the table exists.
            logger.error("Could not create the {} table, so calls won't work: {}", TABLE, e.getMessage());
        }
    }

    private boolean isMySql() {
        try {
            String product = jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            String name = product == null ? "" : product.toLowerCase();
            return name.contains("mysql") || name.contains("mariadb");
        } catch (DataAccessException e) {
            return false;
        }
    }
}
