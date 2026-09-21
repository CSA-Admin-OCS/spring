package com.open.spring.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * Same chain on MySQL 8.4 (Testcontainers). Skipped when Docker is not available.
 * DatabaseModeEnvironmentPostProcessor reads DB_URL before any test property source
 * exists, so the container coordinates are handed over as system properties.
 */
@EnabledIf("com.open.spring.migration.FlywayChainMySqlTest#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bootstrap.enabled=false", "socket.port=0",
                      "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
class FlywayChainMySqlTest {

    static MySQLContainer<?> mysql;

    static {
        if (dockerAvailable()) {
            mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("spring");
            mysql.start();
            System.setProperty("DB_URL", mysql.getJdbcUrl());
            System.setProperty("DB_USERNAME", mysql.getUsername());
            System.setProperty("DB_PASSWORD", mysql.getPassword());
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("DB_URL");
        System.clearProperty("DB_USERNAME");
        System.clearProperty("DB_PASSWORD");
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Test
    void chainAppliesCleanlyAndValidatesOnMySql() throws Exception {
        assertEquals(0, flyway.info().pending().length);
        flyway.validate();
        List<String> applied = new ArrayList<>();
        for (MigrationInfo mi : flyway.info().applied()) {
            applied.add(mi.getVersion().toString());
        }
        assertTrue(applied.containsAll(List.of("1", "2", "3", "4")), "applied " + applied);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `person_seq`")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}
