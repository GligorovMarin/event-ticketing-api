package com.portfolio.ticketing.support;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Empties every table between tests (and puts the TestClock back to real time), so each test starts from a
 * blank slate and cannot be affected by (or break) another test.
 *
 * <p>TRUNCATE ... CASCADE empties the listed tables and everything that references them in one statement.
 * It discovers the tables itself, so a table added by a future migration is cleaned without editing this class.
 * The Flyway history table is skipped: wiping it would make Flyway think no migration ever ran.
 *
 * <p>This is a test-only bean ({@code @Profile("test")}); it lives in src/test so it never ships.
 */
@Component
@Profile("test")
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;
    private final TestClock clock;

    public DatabaseCleaner(JdbcTemplate jdbc, TestClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void clean() {
        // Time travel from the previous test must not leak into this one.
        clock.reset();

        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, String.class);

        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }
}
