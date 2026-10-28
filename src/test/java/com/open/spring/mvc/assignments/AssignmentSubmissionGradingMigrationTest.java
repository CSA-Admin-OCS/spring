package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs the migration on a SQLite table shaped like the one in production before this change,
 * and checks how existing grades are classified.
 */
class AssignmentSubmissionGradingMigrationTest {

    private static final String AI_TEXT = "The submission meets most rubric criteria. " + "Detail. ".repeat(60);

    private Path database;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createLegacyTable() throws Exception {
        database = Files.createTempFile("grading-migration", ".db");
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + database, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE assignment_submission (id bigint not null primary key, grade float, "
                + "feedback varchar(255), ai_summary text, quality_score integer, needs_ai_regrade boolean, "
                + "graded_at datetime, content text, comment varchar(255), assignment_id bigint, submitter_id bigint)");
    }

    @AfterEach
    void deleteDatabase() throws Exception {
        dataSource.destroy();
        Files.deleteIfExists(database);
    }

    private void insert(long id, Double grade, String feedback, String aiSummary) {
        jdbc.update("INSERT INTO assignment_submission (id, grade, feedback, ai_summary) VALUES (?, ?, ?, ?)",
                id, grade, feedback, aiSummary);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT revision, grade, feedback, ai_grade, manual_override, manual_grade, "
                + "manual_feedback FROM assignment_submission WHERE id = ?", id);
    }

    private static boolean flag(Object value) {
        return value instanceof Number number ? number.intValue() == 1 : Boolean.TRUE.equals(value);
    }

    @Test
    void existingGradesAreClassifiedAsAiOrManual() {
        // AI-graded: feedback was the AI text cut to 254 characters plus an ellipsis.
        insert(1, 0.8, AI_TEXT.substring(0, 254) + "…", AI_TEXT);
        // AI-graded with short feedback.
        insert(2, 0.6, "Short AI feedback.", "Short AI feedback.");
        // A teacher re-graded after the AI: their feedback differs from the AI's.
        insert(3, 0.9, "Great work, see me about part 2", AI_TEXT);
        // A teacher graded and the AI never did.
        insert(4, 1.0, "Full marks", null);
        // A teacher graded without feedback.
        insert(5, 0.7, null, "Short AI feedback.");
        // Not graded.
        insert(6, null, null, null);

        new AssignmentSubmissionGradingMigration(jdbc).migrate();

        Map<String, Object> ai = row(1);
        assertEquals(1, ((Number) ai.get("revision")).intValue());
        assertEquals(false, flag(ai.get("manual_override")));
        assertEquals(0.8, ((Number) ai.get("ai_grade")).doubleValue());
        assertEquals(AI_TEXT, ai.get("feedback"), "truncated AI feedback is restored in full");

        assertEquals(false, flag(row(2).get("manual_override")));
        assertEquals(0.6, ((Number) row(2).get("ai_grade")).doubleValue());

        for (long manualId : new long[] {3, 4, 5}) {
            Map<String, Object> manual = row(manualId);
            assertEquals(true, flag(manual.get("manual_override")), "row " + manualId);
            assertEquals(((Number) manual.get("grade")).doubleValue(), ((Number) manual.get("manual_grade")).doubleValue());
            assertNull(manual.get("ai_grade"));
        }
        assertEquals("Great work, see me about part 2", row(3).get("manual_feedback"));

        Map<String, Object> ungraded = row(6);
        assertEquals(false, flag(ungraded.get("manual_override")));
        assertNull(ungraded.get("ai_grade"));
    }

    @Test
    void rowsFlaggedByThePreviousBuildStayManual() {
        jdbc.execute("ALTER TABLE assignment_submission ADD COLUMN graded_manually boolean");
        insert(1, 0.6, "Short AI feedback.", "Short AI feedback.");
        jdbc.update("UPDATE assignment_submission SET graded_manually = 1 WHERE id = 1");

        new AssignmentSubmissionGradingMigration(jdbc).migrate();

        assertEquals(true, flag(row(1).get("manual_override")));
        assertEquals(0.6, ((Number) row(1).get("manual_grade")).doubleValue());
    }

    @Test
    void runningAgainNeverReclassifies() {
        insert(1, 0.6, "Short AI feedback.", "Short AI feedback.");
        AssignmentSubmissionGradingMigration migration = new AssignmentSubmissionGradingMigration(jdbc);
        migration.migrate();
        // After the migration, the app changes feedback (e.g. a teacher override) - a second boot
        // must leave the new state alone.
        jdbc.update("UPDATE assignment_submission SET manual_override = 1, manual_grade = 0.9, grade = 0.9, "
                + "feedback = 'Teacher' WHERE id = 1");

        migration.migrate();

        assertEquals(true, flag(row(1).get("manual_override")));
        assertEquals(0.9, ((Number) row(1).get("grade")).doubleValue());
    }

    @Test
    void theHistoryTableIsCreatedAndAcceptsEvents() {
        new AssignmentSubmissionGradingMigration(jdbc).migrate();

        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, grade, "
                + "created_at) VALUES (1, 1, 'AI_GRADED', 0.5, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO assignment_submission_grade_event (submission_id, revision, event_type, "
                + "created_at) VALUES (1, 2, 'REVISED', CURRENT_TIMESTAMP)");

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_submission_grade_event "
                + "WHERE submission_id = 1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT MAX(id) FROM assignment_submission_grade_event", Integer.class));
    }
}
