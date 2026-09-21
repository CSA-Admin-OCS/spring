package com.open.spring.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fresh SQLite file: the whole Flyway chain (V1 baseline + V2..Vn) runs, then Hibernate
 * validates every entity against the result. If this context loads, the migrations and
 * the entities agree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bootstrap.enabled=false", "socket.port=0",
                      "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
class FlywayChainSqliteTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("chain.db") + "?journal_mode=WAL");
    }

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Test
    void chainAppliesCleanlyAndValidates() throws Exception {
        assertEquals(0, flyway.info().pending().length, "no pending migrations");
        flyway.validate();

        List<String> applied = new ArrayList<>();
        for (MigrationInfo mi : flyway.info().applied()) {
            applied.add(mi.getVersion().toString());
        }
        assertTrue(applied.containsAll(List.of("1", "2", "3", "4")), "applied " + applied);
        assertEquals("4", flyway.info().current().getVersion().toString());

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertTrue(tables.contains("person"));
            assertTrue(tables.contains("flyway_schema_history"));
            assertFalse(tables.stream().anyMatch(t -> t.startsWith("HT_") || t.startsWith("HTE_")),
                    "no Hibernate scratch tables");
            for (String t : tables) {
                if (t.endsWith("_seq")) {
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + t + "\"")) {
                        rs.next();
                        assertEquals(1, rs.getInt(1), t + " must hold exactly one row");
                    }
                }
            }
        }
    }
}
