package com.open.spring.mvc.calls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates call_attendance, the table behind {@link CallAttendanceEntity}, when calls are on, the
 * same way CallRoomTableInitializer creates call_room.
 */
@Component
public class CallAttendanceTableInitializer {

    static final String TABLE = "call_attendance";

    private static final Logger logger = LoggerFactory.getLogger(CallAttendanceTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final CallsProperties properties;

    public CallAttendanceTableInitializer(JdbcTemplate jdbcTemplate, CallsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Bean
    public ApplicationRunner createCallAttendanceTable() {
        return args -> {
            if (properties.isEnabled()) {
                initialize();
            }
        };
    }

    void initialize() {
        boolean mySql = CallTables.isMySql(jdbcTemplate);
        String timestamp = CallTables.timestampType(mySql);
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + CallTables.idColumn(mySql) + ", "
                    + "call_room_id BIGINT NOT NULL, "
                    + "identity VARCHAR(32) NOT NULL, "
                    + "uid VARCHAR(255), "
                    + "roles VARCHAR(255), "
                    + "joined_at " + timestamp + " NOT NULL, "
                    + "left_at " + timestamp + ")");
        } catch (DataAccessException e) {
            // The rest of Spring keeps running; /api/calls answers 503 CALLS_UNAVAILABLE where it needs the table.
            logger.error("Could not create the {} table, so calls won't work: {}", TABLE, e.getMessage());
        }
    }
}
