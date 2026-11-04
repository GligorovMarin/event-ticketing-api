package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TokenFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of the token checks as seen from outside: the full filter chain must answer 401 to every
 * kind of bad token, before any controller runs. (JwtServiceTest checks the decoder in isolation; this checks
 * it is actually WIRED into the security rules.)
 */
@IntegrationTest
class TokenSecurityIT {

    private static final String PROTECTED = "/auth/me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Value("${app.jwt.secret}")
    private String secret;
    @Value("${app.jwt.issuer}")
    private String issuer;

    @BeforeEach
    void emptyDatabase() {
        cleaner.clean();
    }

    private void assertRejected(String authorizationHeader) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", authorizationHeader))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no Authorization header at all: 401")
    void noHeader() throws Exception {
        mockMvc.perform(get(PROTECTED)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the wrong scheme (Basic instead of Bearer): 401")
    void wrongScheme() throws Exception {
        assertRejected("Basic YWRtaW46cGFzc3dvcmQ=");
    }

    @Test
    @DisplayName("a garbage token: 401")
    void garbage() throws Exception {
        assertRejected("Bearer this.is.not-a-jwt");
    }

    @Test
    @DisplayName("a token signed with a different secret (a forgery): 401")
    void forgery() throws Exception {
        TestAccounts.Account real = accounts.organizer("org@example.com");
        String forged = TokenFactory.token("attacker-secret-attacker-secret-attacker", issuer, real.id(), "ORGANIZER",
                Instant.now(), Instant.now().plus(Duration.ofHours(1)));

        assertRejected("Bearer " + forged);
    }

    @Test
    @DisplayName("an expired token: 401")
    void expired() throws Exception {
        TestAccounts.Account real = accounts.customer("ana@example.com");
        String expired = TokenFactory.token(secret, issuer, real.id(), "CUSTOMER",
                Instant.now().minus(Duration.ofHours(3)), Instant.now().minus(Duration.ofHours(2)));

        assertRejected("Bearer " + expired);
    }

    @Test
    @DisplayName("a correctly signed token from a DIFFERENT issuer: 401")
    void foreignIssuer() throws Exception {
        TestAccounts.Account real = accounts.customer("ana@example.com");
        String foreign = TokenFactory.token(secret, "some-other-system", real.id(), "CUSTOMER",
                Instant.now(), Instant.now().plus(Duration.ofHours(1)));

        assertRejected("Bearer " + foreign);
    }

    @Test
    @DisplayName("a token whose payload was edited (CUSTOMER -> ORGANIZER) after signing: 401")
    void tamperedPayload() throws Exception {
        TestAccounts.Account customer = accounts.customer("ana@example.com");
        String[] parts = customer.token().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("CUSTOMER", "ORGANIZER");
        String doctored = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertRejected("Bearer " + doctored);
    }

    @Test
    @DisplayName("an unsigned 'alg: none' token: 401")
    void algNone() throws Exception {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"ORGANIZER\",\"iss\":\"" + issuer + "\"}")
                .getBytes(StandardCharsets.UTF_8));

        assertRejected("Bearer " + header + "." + payload + ".");
    }

    @Test
    @DisplayName("a perfectly valid token is accepted (the control for all the rejections above)")
    void validTokenWorks() throws Exception {
        TestAccounts.Account real = accounts.customer("ana@example.com");

        mockMvc.perform(get(PROTECTED).header("Authorization", real.bearer())).andExpect(status().isOk());
    }
}
