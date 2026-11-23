package com.open.spring.mvc.person;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Adds the nullable person.capstone_mentoring column when it is missing.
 *
 * Hibernate runs with ddl-auto=none and selects every mapped column, so this has to finish before
 * the EntityManagerFactory exists: an ApplicationRunner is too late for beans that query Person
 * while starting and for the first web request. If the column cannot be added, startup stops
 * rather than continuing into failed logins.
 */
@Component(PersonCapstoneColumnMigration.BEAN_NAME)
public class PersonCapstoneColumnMigration implements InitializingBean {

    static final String BEAN_NAME = "personCapstoneColumnMigration";
    private static final String TABLE = "person";
    private static final String COLUMN = "capstone_mentoring";
    private static final Logger logger = LoggerFactory.getLogger(PersonCapstoneColumnMigration.class);

    private final DataSource dataSource;

    public PersonCapstoneColumnMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        addColumnIfMissing();
    }

    void addColumnIfMissing() {
        try (Connection connection = dataSource.getConnection()) {
            // A brand-new database has no table yet; Hibernate's schema build creates it with the column.
            if (!tableExists(connection) || columnExists(connection)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + COLUMN + " JSON NULL");
                // WARN because the root log level is warn, and the deployment check reads this line.
                logger.warn("Added nullable {}.{} column", TABLE, COLUMN);
            } catch (SQLException addFailed) {
                // Another instance starting at the same moment may have added it first.
                if (!columnExists(connection)) {
                    throw addFailed;
                }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Could not add the required " + TABLE + "." + COLUMN
                    + " column, so Spring will not start. Check the database user's ALTER permission. Cause: "
                    + failed.getMessage(), failed);
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), connection.getSchema(), TABLE, null)) {
            while (tables.next()) {
                if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(), TABLE, null)) {
            while (columns.next()) {
                if (TABLE.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && COLUMN.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Makes Hibernate's EntityManagerFactory, and so every Person query, wait for this migration. */
    @Component
    static class RunsBeforeHibernate extends EntityManagerFactoryDependsOnPostProcessor {
        RunsBeforeHibernate() {
            super(BEAN_NAME);
        }
    }
}
