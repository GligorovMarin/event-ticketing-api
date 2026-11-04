package com.portfolio.ticketing.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Wires up the two halves of JWT handling:
 * <ul>
 *   <li>the {@link JwtEncoder}, which SIGNS a new token at login (used by {@link JwtService});</li>
 *   <li>the {@link JwtDecoder}, which VERIFIES the token on every later request. Spring Security finds this
 *       bean by itself (see config/SecurityConfig, {@code oauth2ResourceServer}) and rejects any request whose
 *       token is forged, tampered with, expired or issued by someone else, before a controller runs.</li>
 * </ul>
 *
 * <p>Both use the same HMAC key (HS256, a "symmetric" signature). The algorithm is pinned on the decoder:
 * a token cannot choose how it is verified, which blocks the classic "alg: none" and algorithm-confusion attacks.
 */
@Configuration
class JwtConfig {

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // The default validators check the expiry time ("exp"); we add the issuer check.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
