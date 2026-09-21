package db.migration.common;

import static db.migration.common.MigrationSupport.baselineCreateTable;
import static db.migration.common.MigrationSupport.columnNames;
import static db.migration.common.MigrationSupport.columnType;
import static db.migration.common.MigrationSupport.columnsOf;
import static db.migration.common.MigrationSupport.exec;
import static db.migration.common.MigrationSupport.isMySql;
import static db.migration.common.MigrationSupport.q;
import static db.migration.common.MigrationSupport.tableExists;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.fasterxml.jackson.databind.ObjectMapper;

// verify-allow-change: person, adventure
/**
 * Brings a pre-Flyway database (baseline-stamped at V1, so V1 never ran on it) up to the
 * schema V1 describes. Every step checks the metadata first, so it is a no-op on a fresh
 * database and safe to re-run.
 *
 * <ol>
 *   <li>{@code person.token_version}: add when missing; rebuild the table when a legacy
 *       tool created it as {@code TEXT} (SQLite cannot ALTER COLUMN)</li>
 *   <li>{@code assignment_submission.ai_summary} / {@code .quality_score}: add when missing</li>
 *   <li>{@code adventure}, {@code games}: create when missing (from the V1 DDL);
 *       {@code adventure.details}: add when missing, then backfill from the legacy columns</li>
 * </ol>
 * Replaces scripts/schema_sync.py and the DDL that used to live in ModelInit.
 */
public class V2__Legacy_schema_sync extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        boolean mysql = isMySql(c);

        // 1. person.token_version
        String tvType = columnType(c, "person", "token_version");
        if (tvType == null) {
            exec(c, "ALTER TABLE " + q(c, "person") + " ADD COLUMN " + q(c, "token_version")
                    + " bigint NOT NULL DEFAULT 0");
        } else if (!isIntegerType(tvType)) {
            if (mysql) {
                exec(c, "ALTER TABLE `person` MODIFY `token_version` bigint NOT NULL DEFAULT 0");
            } else {
                rebuildSqliteTable(c, "person", Map.of("token_version",
                        "CAST(COALESCE(\"token_version\", 0) AS INTEGER)"));
            }
        }

        // 2. assignment_submission columns
        if (tableExists(c, "assignment_submission")) {
            if (columnType(c, "assignment_submission", "ai_summary") == null) {
                exec(c, "ALTER TABLE " + q(c, "assignment_submission") + " ADD COLUMN "
                        + q(c, "ai_summary") + " text");
            }
            if (columnType(c, "assignment_submission", "quality_score") == null) {
                exec(c, "ALTER TABLE " + q(c, "assignment_submission") + " ADD COLUMN "
                        + q(c, "quality_score") + " integer");
            }
        }

        // 3. adventure / games
        if (!tableExists(c, "adventure")) {
            exec(c, baselineCreateTable(c, "adventure"));
        }
        if (!tableExists(c, "games")) {
            exec(c, baselineCreateTable(c, "games"));
        }
        if (columnType(c, "adventure", "details") == null) {
            exec(c, "ALTER TABLE " + q(c, "adventure") + " ADD COLUMN " + q(c, "details") + " text");
        }
        backfillAdventureDetails(c);
    }

    private static boolean isIntegerType(String typeName) {
        String t = typeName.toUpperCase(Locale.ROOT);
        return t.startsWith("INT") || t.startsWith("BIGINT") || t.startsWith("SMALLINT")
                || t.startsWith("TINYINT");
    }

    /**
     * SQLite has no ALTER COLUMN: create the table from the V1 DDL under a temporary name,
     * copy every column (applying {@code overrides} expressions where given), swap.
     */
    static void rebuildSqliteTable(Connection c, String table, Map<String, String> overrides) throws Exception {
        String create = baselineCreateTable(c, table);
        List<String> targetCols = columnsOf(create);
        List<String> existing = columnNames(c, table);
        String tmp = table + "__new";
        exec(c, "DROP TABLE IF EXISTS \"" + tmp + "\"");
        exec(c, create.replaceFirst("(?i)create table \"" + table + "\"", "CREATE TABLE \"" + tmp + "\""));
        List<String> insertCols = new ArrayList<>();
        List<String> selectExprs = new ArrayList<>();
        for (String col : targetCols) {
            boolean present = existing.stream().anyMatch(e -> e.equalsIgnoreCase(col));
            if (overrides.containsKey(col) && present) {
                insertCols.add("\"" + col + "\"");
                selectExprs.add(overrides.get(col));
            } else if (present) {
                insertCols.add("\"" + col + "\"");
                selectExprs.add("\"" + col + "\"");
            }
        }
        exec(c, "INSERT INTO \"" + tmp + "\" (" + String.join(", ", insertCols) + ") SELECT "
                + String.join(", ", selectExprs) + " FROM \"" + table + "\"");
        exec(c, "DROP TABLE \"" + table + "\"");
        exec(c, "ALTER TABLE \"" + tmp + "\" RENAME TO \"" + table + "\"");
    }

    /** What ModelInit used to do on every boot, done once and with a real JSON writer. */
    private static void backfillAdventureDetails(Connection c) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String qt = q(c, "adventure");
        String sql = "SELECT " + q(c, "id") + ", " + q(c, "choice_id") + ", " + q(c, "choice_text") + ", "
                + q(c, "choice_is_correct") + ", " + q(c, "answer_is_correct") + ", " + q(c, "answer_content") + ", "
                + q(c, "chat_score") + ", " + q(c, "rubric_ruid") + ", " + q(c, "rubric_criteria")
                + " FROM " + qt + " WHERE " + q(c, "details") + " IS NULL OR TRIM(" + q(c, "details") + ") = ''";
        List<Object[]> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object[] row = new Object[9];
                for (int i = 0; i < 9; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + qt + " SET " + q(c, "details") + " = ? WHERE " + q(c, "id") + " = ?")) {
            for (Object[] r : rows) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("choiceId", r[1]);
                details.put("choiceText", r[2]);
                details.put("choiceIsCorrect", r[3]);
                details.put("answerIsCorrect", r[4]);
                details.put("answerContent", r[5]);
                details.put("chatScore", r[6]);
                details.put("rubricRuid", r[7]);
                details.put("rubricCriteria", r[8]);
                ps.setString(1, mapper.writeValueAsString(details));
                ps.setObject(2, r[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
