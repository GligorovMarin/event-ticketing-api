package com.portfolio.ticketing.config;

import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;

import java.util.List;

/**
 * Who may call what. Spring Security runs this filter chain BEFORE any controller.
 *
 * <p>The rule of the whole project: every endpoint is LOCKED unless it is listed as public below. Forgetting
 * to protect a new endpoint therefore leaves it locked, not open.
 *
 * <p>Two layers of access control work together:
 * <ol>
 *   <li><b>this class</b>: "do you have a valid token at all?" (401 if not);</li>
 *   <li><b>{@code @PreAuthorize("hasRole('ORGANIZER')")}</b> on controller methods (enabled by
 *       {@code @EnableMethodSecurity}): "does your ROLE allow this endpoint?" (403 if not).</li>
 * </ol>
 *
 * <p>Connected to: auth/JwtConfig (the decoder that verifies tokens), auth/JwtService (creates them),
 * {@link OpenApiConfig} (documents the same paths).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Paths anyone may call without logging in, for any HTTP method. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",           // Docker / monitoring: "is the server alive?"
            // Swagger UI. /docs is only a shortcut that REDIRECTS to /swagger-ui/index.html, so the
            // real page and its CSS/JavaScript files under /swagger-ui/ must be public too
            // (found by smoke-testing the production image: the docs page answered 401).
            "/docs", "/docs/**",
            "/swagger-ui/**", "/swagger-ui.html",
            "/v3/api-docs", "/v3/api-docs/**" // the machine-readable API description Swagger UI reads
    };

    /** Public endpoints that only accept POST (creating an account and logging in). */
    private static final String[] PUBLIC_POST_PATHS = {"/auth/register", "/auth/login"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protects browsers that authenticate with COOKIES. This API uses a token in a
                // header and keeps no session, so there is no cookie for an attacker to ride on.
                .csrf(AbstractHttpConfigurer::disable)

                // Stateless: the server remembers nothing between requests. Every request must carry
                // its own proof of identity, the JWT.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        // Default deny: everything not listed above needs a valid token.
                        .anyRequest().authenticated())

                // "Resource server" = this API accepts bearer tokens. Spring reads the Authorization header,
                // verifies the token with the JwtDecoder bean, and answers 401 itself for a missing,
                // malformed, tampered, expired or foreign token. 403 is answered when a valid token
                // lacks the required role.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * Turns the token's {@code role} claim ("ORGANIZER") into the Spring authority "ROLE_ORGANIZER", which is
     * what {@code hasRole('ORGANIZER')} checks. Spring's convention is that "hasRole('X')" looks for "ROLE_X".
     */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString(AuthenticatedUser.ROLE_CLAIM))));
        return converter;
    }

    /**
     * Hashes passwords with bcrypt. The "strength" is a cost: every +1 doubles the time one hash takes, which
     * is the point (it makes guessing passwords slow). Configurable so tests can use the cheapest value.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
