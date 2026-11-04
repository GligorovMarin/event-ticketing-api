package com.portfolio.ticketing.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The {@code app.jwt.*} settings of application.yml as a typed Java object.
 *
 * <p>{@code @ConfigurationProperties} makes Spring copy the settings into this record at startup, and
 * {@code @Validated} checks them. A secret that is missing or too short therefore stops the application
 * from STARTING with a clear message, instead of silently running with weak or no signing.
 *
 * @param secret   signs and verifies every token (HS256). Anyone who knows it can forge a login for any user,
 *                 so production reads it from a secrets manager (environment variable JWT_SECRET).
 * @param issuer   written into every token ("iss") and required when verifying, so tokens made for some
 *                 other system with the same secret are not accepted here.
 * @param lifetime how long a token stays valid. After this the user logs in again.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "app.jwt.secret (env JWT_SECRET) must be at least 32 characters") String secret,
        @NotBlank String issuer,
        @NotNull Duration lifetime) {
}
