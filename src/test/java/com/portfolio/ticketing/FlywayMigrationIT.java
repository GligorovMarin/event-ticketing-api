package com.portfolio.ticketing;

import com.portfolio.ticketing.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks what Flyway did to the real test database: the migrations ran, the table exists, and the
 * constraints written in SQL (which Java cannot express) are really there.
 */
@IntegrationTest
class FlywayMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration was applied successfully")
    void migrationsSucceeded() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the users table has a unique index on e-mail")
    void usersEmailIsUnique() {
        Integer uniqueIndexes = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'users' AND indexname = 'users_email_key' AND indexdef LIKE 'CREATE UNIQUE%'
                """, Integer.class);

        assertThat(uniqueIndexes).isEqualTo(1);
    }

    @Test
    @DisplayName("the database itself refuses an unknown role (CHECK constraint)")
    void unknownRoleIsRejectedByTheDatabase() {
        // Positive control first: a VALID insert must work. Without it, the rejection below could be
        // caused by a typo in our SQL and the test would still pass.
        int inserted = jdbc.update("""
                INSERT INTO users (id, email, full_name, password_hash, role)
                VALUES (gen_random_uuid(), 'valid-role@test.local', 'Valid', 'h', 'CUSTOMER')
                """);
        assertThat(inserted).isEqualTo(1);

        // Bypass the application completely: this is what a buggy script could do. We assert that the
        // error names OUR constraint, proving the rejection came from the CHECK and nothing else.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (id, email, full_name, password_hash, role)
                VALUES (gen_random_uuid(), 'bad-role@test.local', 'Bad', 'h', 'SUPERUSER')
                """))
                .hasMessageContaining("users_role_check");

        // Leave the shared test database as we found it.
        jdbc.update("DELETE FROM users WHERE email = 'valid-role@test.local'");
    }
}
