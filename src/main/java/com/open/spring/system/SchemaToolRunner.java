package com.open.spring.system;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Schema tool mode: {@code --schema.tool=<mode>} boots the application far enough to run
 * Flyway and Hibernate validation, prints a report, and exits the JVM before any other
 * runner executes. Driven by {@code scripts/db_migrate.py}.
 *
 * Modes: info, validate, check, migrate, repair, exit.
 * Always prints one machine-readable line prefixed with {@code SCHEMA_TOOL_JSON }.
 * Exit codes: 0 ok, 1 validation failure, 2 pending or failed migrations (info).
 */
@Component
@ConditionalOnProperty("schema.tool")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaToolRunner implements CommandLineRunner {

    private final ConfigurableApplicationContext ctx;
    private final ObjectProvider<Flyway> flywayProvider;
    private final DataSource dataSource;
    private final String mode;

    public SchemaToolRunner(ConfigurableApplicationContext ctx, ObjectProvider<Flyway> flywayProvider,
                            DataSource dataSource, Environment env) {
        this.ctx = ctx;
        this.flywayProvider = flywayProvider;
        this.dataSource = dataSource;
        this.mode = env.getProperty("schema.tool", "info").trim();
    }

    @Override
    public void run(String... args) {
        int code;
        try {
            code = execute();
        } catch (Exception e) {
            System.out.println("SCHEMA_TOOL_ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            code = 1;
        }
        final int exitCode = code;
        System.out.flush();
        System.exit(SpringApplication.exit(ctx, () -> exitCode));
    }

    private int execute() throws Exception {
        Map<String, Object> report = new TreeMap<>();
        report.put("mode", mode);
        report.put("vendor", vendor());
        if ("exit".equals(mode)) {
            report.put("ok", true);
            emit(report);
            return 0;
        }

        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            report.put("ok", false);
            report.put("error", "flyway is disabled (spring.flyway.enabled=false)");
            emit(report);
            return 1;
        }

        int code = 0;
        switch (mode) {
            case "validate", "check" -> {
                try {
                    flyway.validate();
                    report.put("flywayValid", true);
                } catch (Exception e) {
                    report.put("flywayValid", false);
                    report.put("error", e.getMessage());
                    code = 1;
                }
                // Reaching this runner means the EntityManagerFactory was built, i.e.
                // Hibernate's ddl-auto=validate already passed against this database.
                report.put("hibernateValid", true);
                if ("check".equals(mode)) {
                    List<String> seqWarnings = sequenceWarnings();
                    report.put("sequenceWarnings", seqWarnings);
                    if (!seqWarnings.isEmpty()) {
                        code = 1;
                    }
                }
            }
            case "info", "migrate", "repair" -> {
                // migrate/repair already happened in the FlywayMigrationStrategy
            }
            default -> {
                report.put("error", "unknown schema.tool mode: " + mode);
                emit(report);
                return 1;
            }
        }

        MigrationInfoService info = flyway.info();
        List<String> applied = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-10s %-10s %-45s %s%n", "Version", "State", "Description", "Installed"));
        for (MigrationInfo mi : info.all()) {
            String v = mi.getVersion() == null ? "<repeat>" : mi.getVersion().toString();
            table.append(String.format("%-10s %-10s %-45s %s%n", v, mi.getState().getDisplayName(),
                    mi.getDescription(), mi.getInstalledOn() == null ? "" : mi.getInstalledOn()));
            MigrationState st = mi.getState();
            if (st.isFailed()) {
                failed.add(v);
            } else if (st.isApplied()) {
                applied.add(v);
            } else if (st == MigrationState.PENDING || st == MigrationState.IGNORED) {
                pending.add(v);
            }
        }
        System.out.print(table);
        MigrationInfo current = info.current();
        report.put("current", current == null ? null : String.valueOf(current.getVersion()));
        report.put("applied", applied);
        report.put("pending", pending);
        report.put("failed", failed);
        if ("info".equals(mode) && (!pending.isEmpty() || !failed.isEmpty())) {
            code = 2;
        }
        if ("check".equals(mode) && (!pending.isEmpty() || !failed.isEmpty())) {
            code = 1;
        }
        report.put("ok", code == 0);
        emit(report);
        return code;
    }

    private void emit(Map<String, Object> report) throws Exception {
        System.out.println("SCHEMA_TOOL_JSON " + new ObjectMapper().writeValueAsString(report));
    }

    private String vendor() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            return url != null && url.startsWith("jdbc:mysql:") ? "mysql" : "sqlite";
        }
    }

    /** Warn when a Hibernate table generator would hand out an id that already exists. */
    private List<String> sequenceWarnings() throws Exception {
        List<String> out = new ArrayList<>();
        boolean mysql = "mysql".equals(vendor());
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            String listSql = mysql
                    ? "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                    : "SELECT name FROM sqlite_master WHERE type='table'";
            try (ResultSet rs = st.executeQuery(listSql)) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            String q = mysql ? "`" : "\"";
            for (String seq : tables) {
                if (!seq.endsWith("_seq")) {
                    continue;
                }
                String base = seq.substring(0, seq.length() - 4);
                if (!tables.contains(base)) {
                    continue;
                }
                long nextVal;
                try (ResultSet rs = st.executeQuery("SELECT MAX(next_val) FROM " + q + seq + q)) {
                    nextVal = rs.next() ? rs.getLong(1) : 0;
                }
                long maxId;
                try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + q + base + q)) {
                    maxId = rs.next() ? rs.getLong(1) : 0;
                } catch (Exception noIdColumn) {
                    continue;
                }
                if (maxId > 0 && nextVal <= maxId) {
                    out.add(seq + ": next_val=" + nextVal + " <= max(" + base + ".id)=" + maxId);
                }
            }
        }
        return out;
    }
}
