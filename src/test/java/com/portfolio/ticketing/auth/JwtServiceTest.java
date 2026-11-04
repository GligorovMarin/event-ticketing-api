package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.security.AuthenticatedUser;
import com.portfolio.ticketing.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT test (no Spring, no database) for token creation and verification.
 *
 * <p>We build the real encoder and decoder with {@link JwtConfig}, exactly as the application does, so these
 * tests exercise the REAL signing and checking code, including the attacks the decoder must reject.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    private static final String ISSUER = "event-ticketing-api";

    private final JwtConfig config = new JwtConfig();
    private JwtProperties properties;
    private SecretKey key;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(SECRET, ISSUER, Duration.ofHours(1));
        key = config.jwtSigningKey(properties);
        decoder = config.jwtDecoder(key, properties);
    }

    private JwtService serviceAt(Instant now) {
        return new JwtService(config.jwtEncoder(key), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a token carries the user id, role, issuer and a one-hour expiry")
    void tokenContainsTheExpectedClaims() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        JwtService.IssuedToken token = serviceAt(now).issue(userId, UserRole.ORGANIZER);
        Jwt decoded = decoder.decode(token.value());

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString(AuthenticatedUser.ROLE_CLAIM)).isEqualTo("ORGANIZER");
        // Read the claim as plain text: getIssuer() would try to parse our issuer name as a URL.
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
        // JWT timestamps have whole-second precision, so compare at that precision.
        assertThat(decoded.getExpiresAt()).isEqualTo(decoded.getIssuedAt().plus(Duration.ofHours(1)));
        assertThat(token.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("AuthenticatedUser.from reads the id and role back out of a verified token")
    void authenticatedUserIsBuiltFromTheToken() {
        UUID userId = UUID.randomUUID();
        Jwt decoded = decoder.decode(serviceAt(Instant.now()).issue(userId, UserRole.CUSTOMER).value());

        AuthenticatedUser user = AuthenticatedUser.from(decoded);

        assertThat(user.id()).isEqualTo(userId);
        assertThat(user.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.isOrganizer()).isFalse();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        // Issued two hours ago with a one-hour lifetime: it expired an hour ago.
        String token = serviceAt(Instant.now().minus(Duration.ofHours(2))).issue(UUID.randomUUID(), UserRole.CUSTOMER).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with a different secret (a forgery) is rejected")
    void forgedTokenIsRejected() {
        JwtProperties attackerProperties = new JwtProperties("attacker-secret-attacker-secret-attacker", ISSUER, Duration.ofHours(1));
        JwtConfig attackerConfig = new JwtConfig();
        JwtService attacker = new JwtService(
                attackerConfig.jwtEncoder(attackerConfig.jwtSigningKey(attackerProperties)),
                attackerProperties, Clock.systemUTC());

        String forged = attacker.issue(UUID.randomUUID(), UserRole.ORGANIZER).value();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token whose payload was edited after signing is rejected")
    void tamperedPayloadIsRejected() {
        String token = serviceAt(Instant.now()).issue(UUID.randomUUID(), UserRole.CUSTOMER).value();
        String[] parts = token.split("\\.");

        // Swap CUSTOMER for ORGANIZER in the payload but keep the ORIGINAL signature, as an attacker would.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ORGANIZER");
        String doctored = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(doctored)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from another issuer is rejected even if the signature is valid")
    void foreignIssuerIsRejected() {
        JwtProperties otherIssuer = new JwtProperties(SECRET, "some-other-system", Duration.ofHours(1));
        JwtService foreign = new JwtService(config.jwtEncoder(key), otherIssuer, Clock.systemUTC());

        String token = foreign.issue(UUID.randomUUID(), UserRole.CUSTOMER).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an unsigned 'alg: none' token is rejected (the algorithm is pinned to HS256)")
    void unsignedTokenIsRejected() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"ORGANIZER\",\"iss\":\"" + ISSUER + "\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("garbage is rejected")
    void garbageIsRejected() {
        assertThatThrownBy(() -> decoder.decode("this.is.not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
