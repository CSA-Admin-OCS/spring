package com.open.spring.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production first-run path: a database that has the full schema but no
 * flyway_schema_history, with the drift the legacy tools left behind. Flyway must
 * baseline-stamp it at V1 (without executing V1), then run V2..V4 on top, keeping the data.
 */
class FlywayLegacyBaselineSqliteTest {

    // Plain Flyway (no Spring) cannot scan classpath: locations under Surefire's
    // manifest-only classloader, so point at the sources and list the Java migrations.
    private static final String SQL_LOCATION = "filesystem:src/main/resources/db/migration/sqlite";

    private static org.flywaydb.core.api.configuration.FluentConfiguration configure(String url) {
        return Flyway.configure(FlywayLegacyBaselineSqliteTest.class.getClassLoader())
                .dataSource(url, null, null)
                .locations(SQL_LOCATION)
                .javaMigrations(new db.migration.common.V2__Legacy_schema_sync(),
                                new db.migration.common.V3__Drop_hibernate_temp_tables(),
                                new db.migration.common.V4__Normalize_id_sequences());
    }

    @TempDir
    Path tmp;

    @Test
    void legacyDatabaseIsStampedAndRepaired() throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("legacy.db");

        // 1. Build the current schema, then erase Flyway's memory of it.
        Flyway first = configure(url).load();
        first.migrate();
        List<String> firstRun = new ArrayList<>();
        for (MigrationInfo mi : first.info().all()) {
            firstRun.add(mi.getVersion() + "=" + mi.getState() + "/" + mi.getType());
        }
        assertEquals("4", first.info().current().getVersion().toString(), "fresh chain applied: " + firstRun);
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("DROP TABLE flyway_schema_history");
            // Legacy drift: token_version typed TEXT by a hand-rolled ALTER, scratch tables,
            // a leaked meta table, a stale generator, and some real rows.
            st.execute("ALTER TABLE \"person\" DROP COLUMN \"token_version\"");
            st.execute("ALTER TABLE \"person\" ADD COLUMN \"token_version\" TEXT");
            st.execute("CREATE TABLE \"HTE_person\" (\"rn_\" integer, \"id\" bigint)");
            st.execute("CREATE TABLE \"__migration_meta__\" (\"k\" text, \"v\" text)");
            st.execute("INSERT INTO \"person\" (\"id\", \"email\", \"password\", \"uid\", \"kasm_server_needed\", \"token_version\") "
                    + "VALUES (1, 'a@x', 'p', 'a', 0, '3'), (2, 'b@x', 'p', 'b', 0, NULL), (60, 'c@x', 'p', 'c', 0, '0')");
            // person is not table-generated, so it has no person_seq; announcement_seq is a
            // real generator (the one V4's javadoc calls out) and stands in for the drift.
            st.execute("INSERT INTO \"announcement\" (\"id\", \"title\") VALUES (60, 'a')");
            st.execute("DELETE FROM \"announcement_seq\"");
            st.execute("INSERT INTO \"announcement_seq\" VALUES (1)");
            st.execute("INSERT INTO \"announcement_seq\" VALUES (10)"); // two rows: corrupt generator
        }

        // 2. What the application does at startup.
        Flyway flyway = configure(url).baselineOnMigrate(true).baselineVersion("1").load();
        flyway.migrate();

        List<String> states = new ArrayList<>();
        for (MigrationInfo mi : flyway.info().all()) {
            states.add(mi.getVersion() + "=" + mi.getState());
        }
        MigrationInfo[] applied = flyway.info().applied();
        assertEquals(MigrationState.BASELINE, applied[0].getState(), states.toString());
        assertEquals("1", applied[0].getVersion().toString());
        assertEquals("4", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.info().pending().length);

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            String type = null;
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(\"person\")")) {
                while (rs.next()) {
                    if ("token_version".equals(rs.getString("name"))) {
                        type = rs.getString("type");
                    }
                }
            }
            assertEquals("bigint", type.toLowerCase(), "V2 rebuilt person.token_version");
            try (ResultSet rs = st.executeQuery("SELECT \"id\", \"token_version\" FROM \"person\" ORDER BY \"id\"")) {
                rs.next(); assertEquals(1, rs.getLong(1)); assertEquals(3, rs.getLong(2));
                rs.next(); assertEquals(2, rs.getLong(1)); assertEquals(0, rs.getLong(2));
                rs.next(); assertEquals(60, rs.getLong(1)); assertEquals(0, rs.getLong(2));
                assertFalse(rs.next(), "exactly three rows survive");
            }
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE name IN ('HTE_person','__migration_meta__')")) {
                assertFalse(rs.next(), "V3 dropped scratch/meta tables");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(\"next_val\") FROM \"announcement_seq\"")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "V4 leaves one generator row");
                assertTrue(rs.getLong(2) >= 61, "V4 moved next_val past max(id)");
            }
        }
    }
}
