package com.open.spring.mvc.toolchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Initializes the help-request table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class ToolchainHelpTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(ToolchainHelpTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateCsPathwayHelpRequest() {
        return args -> {
            try {
                // Unquoted lowercase names and plain types so the same statement runs on SQLite and MySQL.
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS cs_pathway_help_request ("
                        + "id varchar(36) NOT NULL PRIMARY KEY, "
                        + "uid varchar(255) NOT NULL, "
                        + "level_key varchar(64) NOT NULL, "
                        + "station varchar(120), "
                        + "message varchar(500) NOT NULL, "
                        + "created_at bigint NOT NULL)");
            } catch (DataAccessException e) {
                logger.warn("Could not create CS Pathway help-request table: {}", e.getMessage());
            }
        };
    }
}
