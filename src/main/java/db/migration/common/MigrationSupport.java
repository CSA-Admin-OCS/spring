package db.migration.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers shared by the Java migrations: vendor detection, metadata checks and access to
 * the frozen V1 baseline DDL (so table definitions are never duplicated in Java).
 */
final class MigrationSupport {

    private MigrationSupport() {
    }

    static boolean isMySql(Connection c) throws SQLException {
        String url = c.getMetaData().getURL();
        return url != null && url.startsWith("jdbc:mysql:");
    }

    static String q(Connection c, String identifier) throws SQLException {
        return isMySql(c) ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }

    static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), null, table, new String[] {"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> tableNames(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_NAME"));
            }
        }
        return out;
    }

    /** Declared type name of a column, or null when the column does not exist. */
    static String columnType(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, "%")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return rs.getString("TYPE_NAME");
                }
            }
        }
        return null;
    }

    static List<String> columnNames(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, "%")) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * The {@code create table} statement for {@code table} from the vendor's frozen V1
     * baseline. Column order in the statement is the order Hibernate expects.
     */
    static String baselineCreateTable(Connection c, String table) throws SQLException, IOException {
        String vendor = isMySql(c) ? "mysql" : "sqlite";
        String path = "db/migration/" + vendor + "/V1__baseline.sql";
        String sql;
        try (InputStream in = MigrationSupport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing classpath resource " + path);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Pattern p = Pattern.compile("(?im)^create table [`\"]" + Pattern.quote(table) + "[`\"] .*?;$");
        Matcher m = p.matcher(sql);
        if (!m.find()) {
            throw new IOException("no create table for " + table + " in " + path);
        }
        String stmt = m.group();
        return stmt.substring(0, stmt.length() - 1); // drop trailing ';'
    }

    /** Column names in the order they appear in a {@code create table} statement. */
    static List<String> columnsOf(String createTableSql) {
        List<String> cols = new ArrayList<>();
        int open = createTableSql.indexOf('(');
        String body = createTableSql.substring(open + 1);
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (char ch : body.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        Pattern col = Pattern.compile("^\\s*[`\"]([^`\"]+)[`\"]\\s+\\S");
        for (String part : parts) {
            Matcher m = col.matcher(part);
            if (m.find() && !part.trim().toLowerCase().startsWith("primary key")) {
                cols.add(m.group(1));
            }
        }
        return cols;
    }
}
