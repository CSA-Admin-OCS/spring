package db.migration.common;

import static db.migration.common.MigrationSupport.columnType;
import static db.migration.common.MigrationSupport.exec;
import static db.migration.common.MigrationSupport.q;
import static db.migration.common.MigrationSupport.tableNames;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

// verify-allow-change: *_seq
/**
 * One-time repair of every Hibernate table generator: each {@code <table>_seq} holds
 * exactly one row and {@code next_val} is at least {@code max(<table>.id) + 1}, so ids
 * handed out after a restore can never collide with existing rows. Replaces the
 * per-request repair the assignment controllers used to do for {@code assignment_seq}.
 */
public class V4__Normalize_id_sequences extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        List<String> tables = tableNames(c);
        for (String seq : tables) {
            if (!seq.endsWith("_seq")) {
                continue;
            }
            String base = seq.substring(0, seq.length() - 4);
            boolean hasBase = tables.stream().anyMatch(t -> t.equalsIgnoreCase(base));
            long maxId = 0;
            if (hasBase && columnType(c, base, "id") != null) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT MAX(" + q(c, "id") + ") FROM " + q(c, base))) {
                    if (rs.next()) {
                        maxId = rs.getLong(1);
                    }
                }
            }
            long nextVal = 0;
            int rows = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(" + q(c, "next_val") + ") FROM " + q(c, seq))) {
                if (rs.next()) {
                    rows = rs.getInt(1);
                    nextVal = rs.getLong(2);
                }
            }
            long target = Math.max(Math.max(nextVal, maxId + 1), 1);
            if (rows != 1 || target != nextVal) {
                exec(c, "DELETE FROM " + q(c, seq));
                exec(c, "INSERT INTO " + q(c, seq) + " (" + q(c, "next_val") + ") VALUES (" + target + ")");
            }
        }
    }
}
