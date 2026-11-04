package com.portfolio.ticketing.auth;

import com.jayway.jsonpath.JsonPath;
import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of registration, login and /auth/me: real application, real PostgreSQL, real bcrypt
 * (cost 4 from application-test.yml), real JWTs. Requests go through MockMvc, which runs the full Spring
 * Security filter chain in memory.
 */
@IntegrationTest
class AuthIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;

    @BeforeEach
    void emptyDatabase() {
        cleaner.clean();
    }

    // ---- small helpers so each test reads like a sentence ----------------------------------------

    private ResultActions register(String email, String fullName, String password, String role) throws Exception {
        return mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","fullName":"%s","password":"%s","role":"%s"}
                """.formatted(email, fullName, password, role)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password)));
    }

    @Nested
    @DisplayName("POST /auth/register")
    class Register {

        @Test
        @DisplayName("creates a CUSTOMER and never returns the password or its hash")
        void createsCustomer() throws Exception {
            register("ana@example.com", "Ana Customer", "long-enough-pw", "CUSTOMER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.email").value("ana@example.com"))
                    .andExpect(jsonPath("$.role").value("CUSTOMER"))
                    .andExpect(content().string(not(containsString("password"))))
                    .andExpect(content().string(not(containsString("long-enough-pw"))));
        }

        @Test
        @DisplayName("creates an ORGANIZER too: both roles may register themselves")
        void createsOrganizer() throws Exception {
            register("org@example.com", "Olga Organizer", "long-enough-pw", "ORGANIZER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("ORGANIZER"));
        }

        @Test
        @DisplayName("stores a bcrypt hash, not the password")
        void storesAHash() throws Exception {
            register("ana@example.com", "Ana", "long-enough-pw", "CUSTOMER").andExpect(status().isCreated());

            String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'ana@example.com'", String.class);

            // "$2a$" / "$2b$" is the bcrypt format marker.
            assertThat(stored).startsWith("$2").doesNotContain("long-enough-pw");
        }

        @Test
        @DisplayName("treats e-mails case-insensitively: a second registration with other capitals is a duplicate")
        void emailIsCaseInsensitive() throws Exception {
            register("  Ana@Example.COM ", "Ana", "long-enough-pw", "CUSTOMER").andExpect(status().isCreated());

            // Stored lower-case and trimmed...
            Integer stored = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'ana@example.com'", Integer.class);
            assertThat(stored).isEqualTo(1);

            // ...so another spelling of the same address is refused by the database's UNIQUE index.
            register("ANA@example.com", "Ana 2", "long-enough-pw", "CUSTOMER")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("rejects two simultaneous registrations of the same e-mail: exactly one wins")
        void concurrentDuplicateRegistration() throws Exception {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(5);
            try {
                java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    results.add(pool.submit(() -> register("race@example.com", "Racer", "long-enough-pw", "CUSTOMER")
                            .andReturn().getResponse().getStatus()));
                }

                int created = 0;
                int conflicts = 0;
                for (var result : results) {
                    int status = result.get();
                    if (status == 201) created++;
                    if (status == 409) conflicts++;
                }

                // The database's UNIQUE index, not a check-then-insert, is what makes this deterministic.
                assertThat(created).isEqualTo(1);
                assertThat(conflicts).isEqualTo(4);
            } finally {
                pool.shutdownNow();
            }
        }

        @ParameterizedTest(name = "rejects {0}")
        @CsvSource(delimiter = '|', textBlock = """
                a password of 7 characters       | ana@example.com | Ana | 1234567        | CUSTOMER
                a password of 73 characters      | ana@example.com | Ana | %s             | CUSTOMER
                an invalid e-mail                | not-an-email    | Ana | long-enough-pw | CUSTOMER
                a blank name                     | ana@example.com | ' ' | long-enough-pw | CUSTOMER
                an unknown role                  | ana@example.com | Ana | long-enough-pw | ADMIN
                """)
        void invalidInput(String description, String email, String name, String password, String role) throws Exception {
            String actualPassword = password.equals("%s") ? "x".repeat(73) : password;

            register(email, name.trim(), actualPassword, role).andExpect(status().isBadRequest());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        }

        @Test
        @DisplayName("lists every invalid field in one response")
        void listsAllFieldErrors() throws Exception {
            register("not-an-email", "Ana", "short", "CUSTOMER")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.email").exists())
                    .andExpect(jsonPath("$.errors.password").exists());
        }

        @Test
        @DisplayName("rejects a password that is under 72 characters but over 72 BYTES")
        void multibytePasswordOverTheLimit() throws Exception {
            // 40 characters x 2 bytes = 80 bytes: bcrypt would silently ignore the last 8.
            register("ana@example.com", "Ana", "é".repeat(40), "CUSTOMER").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects a field the API does not know, instead of silently ignoring it")
        void unknownFieldIsRejected() throws Exception {
            mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                            {"email":"ana@example.com","fullName":"Ana","password":"long-enough-pw","role":"CUSTOMER","isAdmin":true}
                            """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects malformed JSON with a clean 400, not a 500")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /auth/login")
    class Login {

        @BeforeEach
        void anExistingAccount() throws Exception {
            register("ana@example.com", "Ana", "long-enough-pw", "ORGANIZER").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns a Bearer token that works on a protected endpoint")
        void returnsAWorkingToken() throws Exception {
            String body = login("ana@example.com", "long-enough-pw")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                    .andExpect(jsonPath("$.user.role").value("ORGANIZER"))
                    .andReturn().getResponse().getContentAsString();
            String token = JsonPath.read(body, "$.accessToken");

            mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("ana@example.com"));
        }

        @Test
        @DisplayName("accepts the e-mail in any capitalisation")
        void emailCaseDoesNotMatter() throws Exception {
            login("ANA@Example.com", "long-enough-pw").andExpect(status().isOk());
        }

        @Test
        @DisplayName("answers a wrong password and an unknown e-mail with the SAME 401")
        void failuresLookIdentical() throws Exception {
            String wrongPassword = login("ana@example.com", "not-the-password")
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            String unknownEmail = login("nobody@example.com", "whatever-123")
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

            // Identical bodies: the response must not reveal which e-mails have accounts.
            assertThat(unknownEmail).isEqualTo(wrongPassword);
        }

        @Test
        @DisplayName("rejects a missing password with 400")
        void missingPassword() throws Exception {
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ana@example.com\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /auth/me")
    class Me {

        @Test
        @DisplayName("requires a token (401 without one)")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("returns the logged-in user")
        void returnsTheUser() throws Exception {
            TestAccounts.Account ana = accounts.customer("ana@example.com");

            mockMvc.perform(get("/auth/me").header("Authorization", ana.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ana.id().toString()))
                    .andExpect(jsonPath("$.role").value("CUSTOMER"));
        }

        @Test
        @DisplayName("answers 404 when the account was deleted but its token is still valid")
        void deletedAccount() throws Exception {
            TestAccounts.Account ana = accounts.customer("ana@example.com");
            jdbc.update("DELETE FROM users WHERE id = ?", ana.id());

            mockMvc.perform(get("/auth/me").header("Authorization", ana.bearer()))
                    .andExpect(status().isNotFound());
        }
    }
}
