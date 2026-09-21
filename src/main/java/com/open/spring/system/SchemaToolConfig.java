package com.open.spring.system;

import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Decides what Flyway does at startup.
 *
 * <ul>
 *   <li>normal boot, {@code app.db.auto-migrate=true} (default): migrate</li>
 *   <li>{@code AUTO_MIGRATE=false}: validate only and log what is pending; Hibernate's
 *       {@code ddl-auto=validate} then refuses to start on a schema that is behind</li>
 *   <li>{@code --schema.tool=info|validate|check|exit}: report-only, never migrate</li>
 *   <li>{@code --schema.tool=migrate}: migrate regardless of AUTO_MIGRATE</li>
 *   <li>{@code --schema.tool=repair}: flyway repair (clear failed history rows, realign checksums)</li>
 * </ul>
 * {@link SchemaToolRunner} then reports and exits when {@code schema.tool} is set.
 */
@Configuration
public class SchemaToolConfig {

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(Environment env) {
        String tool = env.getProperty("schema.tool", "").trim();
        boolean auto = env.getProperty("app.db.auto-migrate", Boolean.class, true);
        return flyway -> {
            switch (tool) {
                case "info", "validate", "check", "exit" -> {
                    // report-only modes: SchemaToolRunner does the work
                }
                case "repair" -> flyway.repair();
                case "migrate" -> flyway.migrate();
                default -> {
                    if (auto) {
                        flyway.migrate();
                    } else {
                        flyway.validate();
                        MigrationInfo[] pending = flyway.info().pending();
                        if (pending.length > 0) {
                            StringBuilder sb = new StringBuilder("AUTO_MIGRATE=false: ")
                                    .append(pending.length).append(" pending migration(s) NOT applied:");
                            for (MigrationInfo p : pending) {
                                sb.append(' ').append(p.getVersion()).append(" (").append(p.getDescription()).append(')');
                            }
                            System.out.println(sb);
                        }
                    }
                }
            }
        };
    }
}
