package com.open.spring.mvc.gist;

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

@Repository
public class GistConnectionRepository implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private volatile boolean available;
    record StoredConnection(String ciphertext, String githubUsername, String updatedAt) {}

    public GistConnectionRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }
    @Override public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String quote = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL") ? "`" : "\"";
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + quote + "gist_connections" + quote
                + " (person_id bigint PRIMARY KEY, ciphertext text NOT NULL,"
                + " github_username varchar(255) NOT NULL, updated_at varchar(40) NOT NULL)");
            available = true;
        } catch (SQLException | DataAccessException e) {
            LoggerFactory.getLogger(getClass()).error("GitHub connection storage unavailable; other features remain enabled.");
        }
    }
    public boolean isAvailable() { return available; }
    public Optional<StoredConnection> find(long personId) {
        requireAvailable();
        try {
            return jdbc.query("SELECT ciphertext, github_username, updated_at FROM gist_connections WHERE person_id = ?",
                (rs, row) -> new StoredConnection(rs.getString(1), rs.getString(2), rs.getString(3)), personId).stream().findFirst();
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public void save(long personId, String ciphertext, String username) {
        requireAvailable();
        String time = Instant.now().toString();
        try {
            if (jdbc.update("UPDATE gist_connections SET ciphertext = ?, github_username = ?, updated_at = ? WHERE person_id = ?",
                    ciphertext, username, time, personId) == 0) {
                jdbc.update("INSERT INTO gist_connections (person_id, ciphertext, github_username, updated_at) VALUES (?, ?, ?, ?)",
                    personId, ciphertext, username, time);
            }
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public void delete(long personId) {
        requireAvailable();
        try { jdbc.update("DELETE FROM gist_connections WHERE person_id = ?", personId); }
        catch (DataAccessException e) { throw unavailable(); }
    }
    private void requireAvailable() { if (!available) throw unavailable(); }
    private GistException unavailable() {
        return new GistException(503, "GIST_STORAGE_UNAVAILABLE", "GitHub connection storage is unavailable. Contact support.");
    }
}
