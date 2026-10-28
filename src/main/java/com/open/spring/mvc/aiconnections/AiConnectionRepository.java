package com.open.spring.mvc.aiconnections;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AiConnectionRepository implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private volatile boolean available;
    record StoredKey(String ciphertext, String updatedAt) {
        @Override public String toString() { return "StoredKey[redacted]"; }
    }
    public AiConnectionRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc; this.dataSource = dataSource;
    }
    @Override public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String quote = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL") ? "`" : "\"";
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + quote + "ai_connections" + quote
                + " (person_id bigint NOT NULL, provider varchar(16) NOT NULL, ciphertext text NOT NULL,"
                + " updated_at varchar(40) NOT NULL, PRIMARY KEY (person_id, provider))");
            available = true;
        } catch (SQLException | DataAccessException e) {
            LoggerFactory.getLogger(getClass()).error("Personal AI storage unavailable; submissions and system grading remain enabled.");
        }
    }
    public boolean isAvailable() { return available; }
    public Optional<StoredKey> find(long personId, AiProvider provider) {
        requireAvailable();
        try {
            return jdbc.query("SELECT ciphertext, updated_at FROM ai_connections WHERE person_id = ? AND provider = ?",
                (rs, row) -> new StoredKey(rs.getString(1), rs.getString(2)), personId, provider.name()).stream().findFirst();
        } catch (DataAccessException e) { throw AiConnectionException.unavailable(); }
    }
    @Transactional
    public void save(long personId, AiProvider provider, String ciphertext) {
        requireAvailable();
        String time = Instant.now().toString();
        try {
            if (jdbc.update("UPDATE ai_connections SET ciphertext = ?, updated_at = ? WHERE person_id = ? AND provider = ?",
                    ciphertext, time, personId, provider.name()) == 0) {
                jdbc.update("INSERT INTO ai_connections (person_id, provider, ciphertext, updated_at) VALUES (?, ?, ?, ?)",
                    personId, provider.name(), ciphertext, time);
            }
        } catch (DataAccessException e) { throw AiConnectionException.unavailable(); }
    }
    public void delete(long personId, AiProvider provider) {
        requireAvailable();
        try { jdbc.update("DELETE FROM ai_connections WHERE person_id = ? AND provider = ?", personId, provider.name()); }
        catch (DataAccessException e) { throw AiConnectionException.unavailable(); }
    }
    private void requireAvailable() { if (!available) throw AiConnectionException.unavailable(); }
}
