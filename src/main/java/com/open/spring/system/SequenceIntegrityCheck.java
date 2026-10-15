package com.open.spring.system;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Verifies at startup that every Hibernate id generator is ahead of its table.
 *
 * Most entities allocate ids from a `<table>_seq` table rather than from a database
 * AUTO_INCREMENT. Copying rows between environments without copying those tables --
 * which scripts/db_local2prod.py and db_prod2local.py both do -- leaves next_val
 * below MAX(id), and every insert then fails on a primary key collision. In August
 * 2026 that took account creation down: submitter_seq sat at 101 against a MAX(id)
 * of 1167, so no student could sign up.
 *
 * The repair is always safe: advancing a counter past the largest id in use can only
 * skip ids, never overwrite a row. So this runs automatically and logs what it did.
 * Set app.sequence-check.repair=false to detect and warn without writing.
 *
 * Deliberately UPDATE-only. Some of these tables have picked up duplicate rows, which
 * Hibernate does not expect; an UPDATE sets every row to the correct value, so the
 * collision is resolved without deleting anything. Removing the surplus rows is left
 * to scripts/fix_sequences.py, where it is an explicit, backed-up operation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SequenceIntegrityCheck implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SequenceIntegrityCheck.class);

    private static final String SEQ_SUFFIX = "_seq";
    private static final String SEQ_COLUMN = "next_val";

    /** Hibernate's default allocationSize. Leaving a block clear is correct for any optimizer. */
    private static final long ALLOCATION_SIZE = 50;

    private final DataSource dataSource;

    @Value("${app.sequence-check.enabled:true}")
    private boolean enabled;

    @Value("${app.sequence-check.repair:true}")
    private boolean repair;

    public SequenceIntegrityCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            String quote = conn.getMetaData().getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) {
                quote = "\"";
            }
            check(conn, quote);
        } catch (SQLException e) {
            // Never prevent the application from starting over a health check.
            logger.warn("Sequence integrity check could not run: {}", e.getMessage());
        }
    }

    private void check(Connection conn, String q) throws SQLException {
        Map<String, String> tables = listTables(conn);
        List<String> behind = new ArrayList<>();
        int repaired = 0;

        for (Map.Entry<String, String> entry : tables.entrySet()) {
            String lower = entry.getKey();
            if (!lower.endsWith(SEQ_SUFFIX)) {
                continue;
            }
            String seq = entry.getValue();
            String base = tables.get(lower.substring(0, lower.length() - SEQ_SUFFIX.length()));
            if (base == null) {
                continue;
            }

            Long maxId = maxId(conn, q, base);
            if (maxId == null) {
                continue;  // no id column: not an entity table this generator feeds
            }
            List<Long> values = sequenceValues(conn, q, seq);
            if (values.isEmpty()) {
                continue;
            }

            long lowest = values.stream().min(Long::compare).orElse(0L);
            if (lowest > maxId) {
                continue;  // healthy
            }

            long target = maxId + ALLOCATION_SIZE + 1;
            behind.add(String.format("%s(%s <= max %d)", seq, values, maxId));
            if (repair) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("UPDATE " + q + seq + q + " SET " + q + SEQ_COLUMN + q + " = " + target);
                }
                repaired++;
                logger.warn("Sequence {} was behind its table ({} <= max id {}); advanced to {}",
                        seq, values, maxId, target);
            }
            if (values.size() > 1) {
                logger.warn("Sequence {} has {} rows; Hibernate expects one. "
                        + "Run scripts/fix_sequences.py --apply to collapse them.", seq, values.size());
            }
        }

        if (behind.isEmpty()) {
            logger.info("Sequence integrity check: all id generators are ahead of their tables.");
        } else if (repair) {
            logger.warn("Sequence integrity check: repaired {} of {} id generator(s) that were behind "
                    + "their tables. Inserts would have failed on a primary key collision.",
                    repaired, behind.size());
        } else {
            logger.error("Sequence integrity check: {} id generator(s) are BEHIND their tables and "
                    + "repair is disabled. Inserts into these tables will fail on a primary key "
                    + "collision: {}", behind.size(), behind);
        }
    }

    /** Lower-cased name to actual name, so lookups survive a case-sensitive database. */
    private Map<String, String> listTables(Connection conn) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                tables.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return tables;
    }

    private Long maxId(Connection conn, String q, String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(" + q + "id" + q + ") FROM " + q + table + q)) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? 0L : value;
            }
        } catch (SQLException e) {
            return null;  // no id column
        }
        return null;
    }

    private List<Long> sequenceValues(Connection conn, String q, String seq) {
        List<Long> values = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + q + SEQ_COLUMN + q + " FROM " + q + seq + q)) {
            while (rs.next()) {
                values.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            logger.debug("Could not read {}: {}", seq, e.getMessage());
        }
        return values;
    }
}
