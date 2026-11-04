package com.portfolio.ticketing.security;

import com.portfolio.ticketing.user.UserRole;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * "Who is calling", as a small value object for controllers and services.
 *
 * <p>Any controller method can simply declare a parameter of this type; Spring fills it from the verified
 * token (see {@link AuthenticatedUserArgumentResolver}):
 * <pre>{@code
 *   @PostMapping
 *   OrderResponse create(AuthenticatedUser user, @Valid @RequestBody CreateOrderRequest request) { ... }
 * }</pre>
 * It is built only from the token's claims, so no database call is needed per request.
 *
 * @param id   the user's id (the token's {@code sub} claim)
 * @param role the user's role (the token's {@code role} claim)
 */
public record AuthenticatedUser(UUID id, UserRole role) {

    /** Name of the token claim that carries the role. Written by auth/JwtService, read here and in SecurityConfig. */
    public static final String ROLE_CLAIM = "role";

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                UserRole.valueOf(jwt.getClaimAsString(ROLE_CLAIM)));
    }

    public boolean isOrganizer() {
        return role == UserRole.ORGANIZER;
    }
}
