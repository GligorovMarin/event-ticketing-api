package com.portfolio.ticketing.auth.dto;

/**
 * What a successful login returns. The client sends {@code accessToken} back on every later request as the
 * header {@code Authorization: Bearer <accessToken>}.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {
}
