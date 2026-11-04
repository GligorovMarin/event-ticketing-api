package com.portfolio.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Who may call what. Spring Security runs this filter chain BEFORE any controller.
 *
 * <p>The rule of the whole project (same idea as the clinic project): every endpoint is LOCKED unless
 * it is listed as public below. Forgetting to protect a new endpoint therefore leaves it locked,
 * not open. Login and the JWT checks are added in Milestone 2.
 *
 * <p>Connected to: application.yml (nothing yet), {@link OpenApiConfig} (documents the same paths).
 */
@Configuration
public class SecurityConfig {

    /** Paths anyone may call without logging in. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",           // Docker / monitoring: "is the server alive?"
            // Swagger UI. /docs is only a shortcut that REDIRECTS to /swagger-ui/index.html, so the
            // real page and its CSS/JavaScript files under /swagger-ui/ must be public too
            // (found by smoke-testing the production image: the docs page answered 401).
            "/docs", "/docs/**",
            "/swagger-ui/**", "/swagger-ui.html",
            "/v3/api-docs", "/v3/api-docs/**" // the machine-readable API description Swagger UI reads
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protects browsers that authenticate with COOKIES. This API uses a token in a
                // header and keeps no session, so there is no cookie for an attacker to ride on.
                .csrf(AbstractHttpConfigurer::disable)

                // Stateless: the server remembers nothing between requests. Every request must carry
                // its own proof of identity (the JWT, from Milestone 2).
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Default deny: everything not listed above needs a login.
                        .anyRequest().authenticated())

                // 401 Unauthorized ("I do not know who you are") for anonymous callers. Without
                // this, Spring answers 403, which would wrongly say "I know you, and no".
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}
