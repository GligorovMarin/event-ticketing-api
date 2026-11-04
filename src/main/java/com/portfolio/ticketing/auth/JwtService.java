package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.security.AuthenticatedUser;
import com.portfolio.ticketing.user.UserRole;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates the login token.
 *
 * <p>A JWT is three Base64 parts, {@code header.payload.signature}. The payload holds "claims" (facts about
 * the user); the signature proves the server wrote them and nobody edited them since. We put in:
 * <ul>
 *   <li>{@code sub} (subject): the user id;</li>
 *   <li>{@code role}: CUSTOMER or ORGANIZER, so most requests need no database lookup to know what you may do;</li>
 *   <li>{@code iss}, {@code iat}, {@code exp}: issuer, issued-at, expires-at.</li>
 * </ul>
 * The payload is only encoded, NOT encrypted: anyone holding the token can read it, so never put secrets in it.
 *
 * <p>The {@link Clock} is injected (not {@code Instant.now()}) so tests can freeze time and check the exact expiry.
 * Verification of incoming tokens is done by Spring Security using the decoder from {@link JwtConfig}.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** A signed token and how long it lives, as returned to the client at login. */
    public record IssuedToken(String value, long expiresInSeconds) {
    }

    public IssuedToken issue(UUID userId, UserRole role) {
        Instant now = clock.instant();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.lifetime()))
                .claim(AuthenticatedUser.ROLE_CLAIM, role.name())
                .build();

        // The header names the signing algorithm; the decoder insists on HS256 and ignores anything else.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.lifetime().toSeconds());
    }
}
