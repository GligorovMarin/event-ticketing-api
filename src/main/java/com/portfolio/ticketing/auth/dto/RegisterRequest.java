package com.portfolio.ticketing.auth.dto;

import com.portfolio.ticketing.common.Emails;
import com.portfolio.ticketing.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/register}.
 *
 * <p>A Java <b>record</b> is an immutable data holder: the compiler writes the constructor and the getters.
 * The annotations are Bean Validation rules, checked because the controller parameter is marked
 * {@code @Valid}; a broken rule becomes a 400 response listing every bad field (common/ApiExceptionHandler).
 *
 * <p>The password rules are about LENGTH, not "must contain a symbol": current guidance (NIST) prefers long
 * passwords over complexity tricks. The maximum is 72 because bcrypt only reads the first 72 bytes.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull UserRole role) {

    /** A "compact constructor": runs before the fields are assigned, so we can clean the e-mail first. */
    public RegisterRequest {
        email = Emails.normalize(email);
    }
}
