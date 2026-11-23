package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The column on a real SQLite database: null rows, stale saves, overlaps, and public names. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersonDetailsService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonCapstonePersistenceTest {

    private static final String PROJECT = "/capstone/example/";
    private static final Path DATABASE = createDatabaseFile();

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?journal_mode=WAL&busy_timeout=5000");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private static Path createDatabaseFile() {
        try {
            return Files.createTempFile("capstone-mentoring-test", ".db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void deleteDatabase() throws IOException {
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
    }

    @Autowired private PersonDetailsService service;
    @Autowired private PersonJpaRepository people;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private PasswordEncoder passwordEncoder;

    private Person account(String role) {
        String uid = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Person person = new Person(uid + "@example.com", uid, "password", "sid-" + uid, uid + " Name", null, false, role);
        return people.save(person);
    }

    private void change(Person person, java.util.function.Consumer<Person> action) {
        service.changeCapstoneMentoring(person.getUid(), target -> {
            action.accept(target);
            return null;
        });
    }

    private String status(Person person, String project) {
        return service.readCapstoneMentoring(person.getUid(), target -> target.capstoneStatus(project));
    }

    @Test
    void anExistingPersonWithANullColumnLoadsAndSavesUnchanged() {
        Person person = account("ROLE_USER");
        Person loaded = people.findByUid(person.getUid());
        assertNull(loaded.getCapstoneMentoring());
        loaded.setName("Renamed Person");
        people.save(loaded);
        assertEquals("Renamed Person", people.findByUid(person.getUid()).getName());
        assertNull(jdbc.queryForObject("SELECT capstone_mentoring FROM person WHERE id = ?", String.class, person.getId()));
    }

    @Test
    void aStaleSaveCannotOverwriteAnApproval() {
        Person mentor = account("ROLE_MENTOR");
        Person stale = people.findByUid(mentor.getUid());

        change(mentor, target -> target.requestCapstone(PROJECT));
        service.decideCapstoneRequest(mentor.getId(), PROJECT, "teacher1", true);

        stale.setName("Saved From A Stale Copy");
        people.save(stale);

        assertEquals("Saved From A Stale Copy", people.findByUid(mentor.getUid()).getName());
        assertEquals("approved", status(mentor, PROJECT));
    }

    @Test
    void clearingTheLastChoiceStoresNull() {
        Person mentor = account("ROLE_MENTOR");
        change(mentor, target -> target.markCapstoneInterested(PROJECT));
        change(mentor, target -> target.clearCapstoneChoice(PROJECT));
        assertNull(jdbc.queryForObject("SELECT capstone_mentoring FROM person WHERE id = ?", String.class, mentor.getId()));
    }

    @Test
    void aRefusedChangeSavesNothing() {
        Person mentor = account("ROLE_MENTOR");
        change(mentor, target -> target.skipCapstone(PROJECT));
        try {
            change(mentor, target -> target.requestCapstone(PROJECT));
        } catch (IllegalStateException expected) {
            // The rule refused it; the stored state is checked below.
        }
        assertEquals("skipped", status(mentor, PROJECT));
    }

    @Test
    void overlappingChangesNeverLoseAWriteThatReportedSuccess() throws Exception {
        Person mentor = account("ROLE_MENTOR");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                String project = "/capstone/overlap-" + i + "/";
                Callable<String> task = () -> {
                    try {
                        change(mentor, target -> target.markCapstoneInterested(project));
                        return project;
                    } catch (ConcurrencyFailureException overlap) {
                        return null;
                    }
                };
                results.add(pool.submit(task));
            }
            int saved = 0;
            for (Future<String> result : results) {
                String project = result.get();
                if (project != null) {
                    saved += 1;
                    assertEquals("interested", status(mentor, project), project + " reported success but was lost");
                }
            }
            assertTrue(saved > 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void decidingOneRequestLeavesTheMentorsOtherProjectsAndOtherMentorsAlone() {
        Person mentor = account("ROLE_MENTOR");
        Person other = account("ROLE_MENTOR");
        for (String project : List.of("/capstone/one/", "/capstone/two/", "/capstone/three/")) {
            change(mentor, target -> target.requestCapstone(project));
        }
        change(other, target -> target.requestCapstone("/capstone/one/"));

        service.decideCapstoneRequest(mentor.getId(), "/capstone/one/", "teacher1", true);
        service.decideCapstoneRequest(mentor.getId(), "/capstone/two/", "teacher1", false);

        assertEquals("approved", status(mentor, "/capstone/one/"));
        assertEquals("denied", status(mentor, "/capstone/two/"));
        assertEquals("pending", status(mentor, "/capstone/three/"));
        assertEquals("pending", status(other, "/capstone/one/"));
    }

    @Test
    void publicNamesShowOnlyApprovedMentorsWhoStillHoldTheRole() {
        Person active = account("ROLE_MENTOR");
        Person removed = account("ROLE_MENTOR");
        Person waiting = account("ROLE_MENTOR");
        String project = "/capstone/public-names/";
        for (Person mentor : List.of(active, removed)) {
            change(mentor, target -> target.requestCapstone(project));
            service.decideCapstoneRequest(mentor.getId(), project, "teacher1", true);
        }
        change(waiting, target -> target.requestCapstone(project));

        Person withoutRole = people.findByUid(removed.getUid());
        withoutRole.setRoles(new ArrayList<>(List.of("ROLE_USER")));
        people.save(withoutRole);

        List<String> names = service.approvedCapstoneMentorNames().get(project);
        assertEquals(List.of(active.getName()), names);
        assertEquals("approved", status(removed, project), "the stored record is kept");
        assertFalse(names.toString().contains("@"));
    }

    @Test
    void theStaffListHasNameAndProjectButNoEmailOrUid() {
        Person mentor = account("ROLE_MENTOR");
        String project = "/capstone/staff-list/";
        change(mentor, target -> target.requestCapstone(project));
        var row = service.pendingCapstoneRequests().stream()
                .filter(request -> project.equals(request.get("project"))).findFirst().orElseThrow();
        assertEquals(mentor.getName(), row.get("mentor"));
        assertFalse(row.containsKey("email"));
        assertFalse(row.containsKey("uid"));
    }
}
