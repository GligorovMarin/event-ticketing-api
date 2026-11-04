package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.auth.dto.LoginRequest;
import com.portfolio.ticketing.auth.dto.LoginResponse;
import com.portfolio.ticketing.auth.dto.RegisterRequest;
import com.portfolio.ticketing.auth.dto.UserResponse;
import com.portfolio.ticketing.common.BadRequestException;
import com.portfolio.ticketing.common.ConflictException;
import com.portfolio.ticketing.common.InvalidCredentialsException;
import com.portfolio.ticketing.common.NotFoundException;
import com.portfolio.ticketing.user.User;
import com.portfolio.ticketing.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Registration, login and "who am I".
 *
 * <p>Connections: called by {@link AuthController}; stores users through {@link UserRepository}; hashes and
 * checks passwords with the {@link PasswordEncoder} bean (config/SecurityConfig); signs tokens with {@link JwtService}.
 */
@Service
public class AuthService {

    /** bcrypt silently ignores everything after the 72nd BYTE, so we refuse longer passwords instead. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * A hash of a throwaway password, used when the e-mail does not exist so that a failed login takes as long
     * as one with a real account. Without it, "unknown e-mail" would answer instantly while "wrong password"
     * took ~100 ms of bcrypt, and an attacker could measure the difference to find which e-mails have accounts.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-equalisation-password");
    }

    /**
     * Creates an account. The role comes from the request: both roles are open to self-registration (see
     * user/UserRole for why).
     *
     * <p>We do NOT check "does this e-mail exist?" first and insert afterwards: two simultaneous requests could
     * both pass the check. We insert and let the database's UNIQUE index decide, then translate its error.
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BadRequestException("The password must be at most " + BCRYPT_MAX_BYTES + " bytes long");
        }

        User user = new User(
                request.email(),
                request.fullName().trim(),
                passwordEncoder.encode(request.password()),
                request.role());

        try {
            // saveAndFlush sends the INSERT now (plain save may wait until commit), so a duplicate is
            // detected inside this try block.
            return UserResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException duplicate) {
            // Trade-off worth knowing: this tells a stranger "that e-mail has an account". Hiding it would
            // need an e-mail verification flow, which is out of scope here.
            throw new ConflictException("An account with this email already exists");
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = users.findByEmail(request.email()).orElse(null);

        // ALWAYS run exactly one bcrypt comparison, whether or not the account exists (see dummyHash).
        String hashToCheck = user != null ? user.getPasswordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        // ONE message for "no such e-mail" and "wrong password".
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        JwtService.IssuedToken token = jwtService.issue(user.getId(), user.getRole());
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), UserResponse.from(user));
    }

    /** Reads the user fresh from the database (the token only carries an id and a role). */
    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                // Possible when the account was deleted while its token was still valid.
                .orElseThrow(() -> new NotFoundException("User no longer exists"));
    }
}
