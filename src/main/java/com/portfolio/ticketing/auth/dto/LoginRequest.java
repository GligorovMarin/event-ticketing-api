package com.portfolio.ticketing.auth.dto;

import com.portfolio.ticketing.common.Emails;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /auth/login}. No length rules on the password here: login must answer "wrong e-mail
 * or password" for ANY wrong guess, not reveal the password policy through different messages.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
