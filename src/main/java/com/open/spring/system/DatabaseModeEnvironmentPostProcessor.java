package com.open.spring.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

public class DatabaseModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "databaseModeOverrides";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // DB_URL is what .env sets; spring.datasource.url covers explicit overrides
        // (tests, --spring.datasource.url=... on the schema tool) when DB_URL is blank.
        String dbUrl = environment.getProperty("DB_URL", "").trim();
        if (dbUrl.isEmpty()) {
            String dsUrl = environment.getProperty("spring.datasource.url", "").trim();
            if (!dsUrl.startsWith("${")) {
                dbUrl = dsUrl;
            }
        }
        boolean mysqlMode = dbUrl.startsWith("jdbc:mysql:");

        Map<String, Object> overrides = new HashMap<>();
        if (mysqlMode) {
            overrides.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
            overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect");
        } else {
            overrides.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
            overrides.put("spring.jpa.database-platform", "com.open.spring.system.SQLiteValidatingDialect");
        }

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(SOURCE_NAME)) {
            sources.remove(SOURCE_NAME);
        }
        sources.addFirst(new MapPropertySource(SOURCE_NAME, overrides));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}