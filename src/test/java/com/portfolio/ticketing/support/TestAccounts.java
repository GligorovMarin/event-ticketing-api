package com.portfolio.ticketing.support;

import com.portfolio.ticketing.auth.JwtService;
import com.portfolio.ticketing.user.User;
import com.portfolio.ticketing.user.UserRepository;
import com.portfolio.ticketing.user.UserRole;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates ready-to-use accounts for tests: a user row in the database plus a genuine, signed token.
 *
 * <p>Going straight to the repository and {@link JwtService} (instead of calling the register and login
 * endpoints in every test) keeps tests short and independent of the code they are NOT testing. The tokens are
 * real: the application verifies them exactly as it verifies tokens from /auth/login.
 */
@Component
@Profile("test")
public class TestAccounts {

    /** The password of every test account (cheap to type, only ever protects a throwaway database). */
    public static final String PASSWORD = "Password123!";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public TestAccounts(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** A test account: who it is and a valid token to act as them. */
    public record Account(UUID id, String email, UserRole role, String token) {

        /** The value of the Authorization header: {@code Authorization: Bearer <token>}. */
        public String bearer() {
            return "Bearer " + token;
        }
    }

    public Account create(UserRole role, String email) {
        User user = users.saveAndFlush(new User(email, "Test " + role, passwordEncoder.encode(PASSWORD), role));

        return new Account(user.getId(), email, role, jwtService.issue(user.getId(), role).value());
    }

    public Account customer(String email) {
        return create(UserRole.CUSTOMER, email);
    }

    public Account organizer(String email) {
        return create(UserRole.ORGANIZER, email);
    }
}
