package db.migration.common;

import static db.migration.common.MigrationSupport.exec;
import static db.migration.common.MigrationSupport.q;
import static db.migration.common.MigrationSupport.tableNames;

import java.sql.Connection;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

// verify-allow-drop: HT_*, HTE_*, __migration_meta__
/**
 * Drops Hibernate's session-scoped scratch tables ({@code HT_*}, {@code HTE_*}) that older
 * startups created, plus the {@code __migration_meta__} table a backup restore leaked in.
 * None hold durable data; Hibernate no longer creates them
 * (hibernate.query.mutation_strategy.persistent.create_tables=false).
 */
public class V3__Drop_hibernate_temp_tables extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        for (String t : tableNames(c)) {
            if (t.startsWith("HT_") || t.startsWith("HTE_") || t.equals("__migration_meta__")) {
                exec(c, "DROP TABLE " + q(c, t));
            }
        }
    }
}
