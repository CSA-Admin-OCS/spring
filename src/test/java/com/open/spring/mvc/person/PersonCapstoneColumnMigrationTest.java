package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/** The startup step that adds person.capstone_mentoring to an existing database. */
class PersonCapstoneColumnMigrationTest {

    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach
    void close() {
        source.destroy();
    }

    private long columnCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('person') WHERE name = 'capstone_mentoring'", Long.class);
    }

    @Test
    void addsTheColumnOnceAndLeavesExistingRowsUnchanged() {
        jdbc.execute("CREATE TABLE person (id INTEGER PRIMARY KEY, uid TEXT, roles_json TEXT)");
        jdbc.update("INSERT INTO person (id, uid, roles_json) VALUES (1, 'toby', '[\"ROLE_ADMIN\"]')");
        PersonCapstoneColumnMigration migration = new PersonCapstoneColumnMigration(source);

        migration.addColumnIfMissing();
        migration.addColumnIfMissing();

        assertEquals(1, columnCount());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM person WHERE id = 1");
        assertEquals("toby", row.get("uid"));
        assertEquals("[\"ROLE_ADMIN\"]", row.get("roles_json"));
        assertEquals(null, row.get("capstone_mentoring"));
    }

    @Test
    void keepsAStoredChoiceOnALaterStartup() {
        jdbc.execute("CREATE TABLE person (id INTEGER PRIMARY KEY, capstone_mentoring JSON)");
        String stored = "{\"/capstone/a/\":{\"status\":\"approved\"}}";
        jdbc.update("INSERT INTO person (id, capstone_mentoring) VALUES (1, ?)", stored);

        new PersonCapstoneColumnMigration(source).addColumnIfMissing();

        assertEquals(stored, jdbc.queryForObject("SELECT capstone_mentoring FROM person", String.class));
    }

    @Test
    void leavesABrandNewDatabaseForHibernateToBuild() {
        new PersonCapstoneColumnMigration(source).addColumnIfMissing();
        List<String> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'table'", String.class);
        assertFalse(tables.contains("person"));
    }

    @Test
    void stopsStartupWithAClearErrorWhenTheDatabaseFails() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("access denied"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                new PersonCapstoneColumnMigration(broken)::afterPropertiesSet);

        assertTrue(failure.getMessage().contains("person.capstone_mentoring"));
        assertTrue(failure.getMessage().contains("access denied"));
    }

    @Test
    void hibernateWaitsForTheMigration() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("entityManagerFactory",
                new RootBeanDefinition(LocalContainerEntityManagerFactoryBean.class));

        new PersonCapstoneColumnMigration.RunsBeforeHibernate().postProcessBeanFactory(factory);

        String[] dependsOn = factory.getBeanDefinition("entityManagerFactory").getDependsOn();
        assertTrue(Arrays.asList(dependsOn).contains(PersonCapstoneColumnMigration.BEAN_NAME));
    }
}
