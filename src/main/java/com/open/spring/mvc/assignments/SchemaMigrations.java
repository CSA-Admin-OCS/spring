package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent schema steps for the startup migrations (Hibernate runs with ddl-auto=none). Statements
 * are unquoted so they run on both SQLite and MySQL.
 */
final class SchemaMigrations {

    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrations.class);

    private SchemaMigrations() {
    }

    static boolean isMySql(JdbcTemplate jdbc) {
        String product = jdbc.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        return product != null && product.toLowerCase().contains("mysql");
    }

    /** Adds a column; does nothing if it already exists. */
    static void addColumn(JdbcTemplate jdbc, String table, String columnDefinition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
            logger.info("Added {} column to {}", columnDefinition, table);
        } catch (DataAccessException e) {
            // Already exists.
        }
    }

    /** Creates an index; does nothing if it already exists (MySQL has no IF NOT EXISTS here). */
    static void createIndex(JdbcTemplate jdbc, String name, String table, String column) {
        try {
            jdbc.execute("CREATE INDEX " + name + " ON " + table + " (" + column + ")");
        } catch (DataAccessException e) {
            // Already exists.
        }
    }

    /** Changes a varchar column to TEXT on MySQL; SQLite doesn't enforce declared lengths. */
    static void widenToText(JdbcTemplate jdbc, String table, String column) {
        if (!isMySql(jdbc)) {
            return;
        }
        try {
            String type = jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, table, column);
            if (!"text".equalsIgnoreCase(type)) {
                jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " TEXT");
                logger.info("Changed {}.{} from {} to TEXT", table, column, type);
            }
        } catch (DataAccessException e) {
            logger.warn("Could not change {}.{} to TEXT: {}", table, column, e.getMessage());
        }
    }

    static String autoIncrementId(boolean mysql) {
        return mysql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    static String timestampType(boolean mysql) {
        return mysql ? "DATETIME(6)" : "TIMESTAMP";
    }

    static String doubleType(boolean mysql) {
        return mysql ? "DOUBLE" : "FLOAT";
    }
}
