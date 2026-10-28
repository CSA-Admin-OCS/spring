package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Columns for revisions, AI grades and manual overrides on assignment_submission, TEXT for its
 * free-text columns, and the grading history table.
 *
 * Existing graded rows are classified once (while manual_override is NULL): rows flagged
 * graded_manually, or whose feedback isn't the AI's text, become manual overrides; the rest become
 * AI grades with their truncated feedback restored from ai_summary. Ambiguous rows count as manual
 * so the AI can't replace a teacher's grade.
 */
@Component
@RequiredArgsConstructor
public class AssignmentSubmissionGradingMigration {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentSubmissionGradingMigration.class);

    private static final String TABLE = "assignment_submission";

    // The old feedback column held 254 characters of the AI text plus an ellipsis.
    private static final int LEGACY_FEEDBACK_PREFIX = 254;

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migrateSubmissionGrading() {
        return args -> migrate();
    }

    void migrate() {
        boolean mysql = SchemaMigrations.isMySql(jdbcTemplate);
        for (String column : new String[] {"revision INTEGER", "ai_grade FLOAT", "manual_override BOOLEAN",
                "manual_grade FLOAT", "manual_feedback TEXT"}) {
            SchemaMigrations.addColumn(jdbcTemplate, TABLE, column);
        }
        SchemaMigrations.widenToText(jdbcTemplate, TABLE, "feedback");
        SchemaMigrations.widenToText(jdbcTemplate, TABLE, "comment");
        backfill();
        createEventTable(mysql);
    }

    private void backfill() {
        jdbcTemplate.update("UPDATE assignment_submission SET revision = 1 WHERE revision IS NULL");
        try {
            jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 1, manual_grade = grade, "
                    + "manual_feedback = feedback WHERE manual_override IS NULL AND graded_manually = 1");
        } catch (DataAccessException e) {
            // graded_manually only exists on databases that ran the previous build.
        }
        int manual = jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 1, manual_grade = grade, "
                + "manual_feedback = feedback WHERE manual_override IS NULL AND grade IS NOT NULL "
                + "AND (ai_summary IS NULL OR feedback IS NULL OR SUBSTR(feedback, 1, ?) <> SUBSTR(ai_summary, 1, ?))",
                LEGACY_FEEDBACK_PREFIX, LEGACY_FEEDBACK_PREFIX);
        int ai = jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 0, ai_grade = grade, "
                + "feedback = ai_summary WHERE manual_override IS NULL AND grade IS NOT NULL");
        jdbcTemplate.update("UPDATE assignment_submission SET manual_override = 0 WHERE manual_override IS NULL");
        if (manual + ai > 0) {
            logger.info("Classified existing grades: {} manual, {} AI", manual, ai);
        }
    }

    private void createEventTable(boolean mysql) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS assignment_submission_grade_event ("
                + SchemaMigrations.autoIncrementId(mysql) + ", "
                + "submission_id BIGINT NOT NULL, "
                + "revision INTEGER NOT NULL, "
                + "event_type VARCHAR(40) NOT NULL, "
                + "grade " + SchemaMigrations.doubleType(mysql) + ", "
                + "feedback TEXT, "
                + "effective BOOLEAN, "
                + "actor_id BIGINT, "
                + "content TEXT, "
                + "created_at " + SchemaMigrations.timestampType(mysql) + " NOT NULL)");
        SchemaMigrations.createIndex(jdbcTemplate, "idx_grade_event_submission",
                "assignment_submission_grade_event", "submission_id");
    }
}
