package com.open.spring.mvc.assignments;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Columns for closing assignments and the teacher review table. Runs after
 * {@link AssignmentSubmissionGradingMigration}, which creates the grade event table.
 */
@Component
@RequiredArgsConstructor
public class AssignmentClosureMigration {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public ApplicationRunner migrateAssignmentClosure() {
        return args -> migrate();
    }

    void migrate() {
        boolean mysql = SchemaMigrations.isMySql(jdbcTemplate);
        SchemaMigrations.addColumn(jdbcTemplate, "assignment", "closed_at " + SchemaMigrations.timestampType(mysql));
        SchemaMigrations.addColumn(jdbcTemplate, "assignment", "closed_by_id BIGINT");
        SchemaMigrations.addColumn(jdbcTemplate, "assignment_submission_grade_event", "review_id BIGINT");
        createReviewTable(mysql);
    }

    private void createReviewTable(boolean mysql) {
        String timestamp = SchemaMigrations.timestampType(mysql);
        String decimal = SchemaMigrations.doubleType(mysql);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS assignment_grade_review ("
                + SchemaMigrations.autoIncrementId(mysql) + ", "
                + "submission_id BIGINT NOT NULL, "
                + "assignment_id BIGINT NOT NULL, "
                + "reason VARCHAR(30) NOT NULL, "
                + "status VARCHAR(10) NOT NULL, "
                + "grade_at_open " + decimal + ", "
                + "evidence TEXT, "
                + "ai_grade " + decimal + ", "
                + "ai_feedback TEXT, "
                + "similar_submissions TEXT, "
                + "decision VARCHAR(10), "
                + "rationale TEXT, "
                + "adjusted_grade " + decimal + ", "
                + "adjusted_feedback TEXT, "
                + "opened_by_id BIGINT NOT NULL, "
                + "opened_at " + timestamp + " NOT NULL, "
                + "decided_by_id BIGINT, "
                + "decided_at " + timestamp + ")");
        SchemaMigrations.createIndex(jdbcTemplate, "idx_grade_review_submission", "assignment_grade_review", "submission_id");
    }
}
