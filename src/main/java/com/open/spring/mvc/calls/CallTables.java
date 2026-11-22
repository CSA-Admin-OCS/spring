package com.open.spring.mvc.calls;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** What the calls table initializers share: the same SQL has to run on SQLite and on MySQL. */
final class CallTables {

    private CallTables() {
    }

    static boolean isMySql(JdbcTemplate jdbcTemplate) {
        try {
            String product = jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            String name = product == null ? "" : product.toLowerCase();
            return name.contains("mysql") || name.contains("mariadb");
        } catch (DataAccessException e) {
            return false;
        }
    }

    static String idColumn(boolean mySql) {
        return mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    static String timestampType(boolean mySql) {
        return mySql ? "DATETIME(6)" : "TIMESTAMP";
    }
}
