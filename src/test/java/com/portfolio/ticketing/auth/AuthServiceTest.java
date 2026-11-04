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
import com.portfolio.ticketing.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UNIT test of AuthService with its collaborators replaced by Mockito "mocks": stand-in objects whose
 * answers we script and whose calls we can inspect. No Spring, no database: these tests check the SERVICE'S
 * own decisions (what it stores, what it refuses, how failures look). The real database behaviour
 * (unique index, JSON mapping) is covered by AuthIT.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        // The constructor hashes a throwaway password (for timing equalisation), so script the encoder first.
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        service = new AuthService(users, passwordEncoder, jwtService);
    }

    private static RegisterRequest registration(String password) {
        return new RegisterRequest("  Ana@Example.COM ", " Ana Patient ", password, UserRole.CUSTOMER);
    }

    @Test
    @DisplayName("register stores the HASH of the password, the cleaned e-mail and the chosen role")
    void registerStoresAHashedPassword() {
        when(passwordEncoder.encode("long-enough-pw")).thenReturn("bcrypt-hash-of-it");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = service.register(registration("long-enough-pw"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("bcrypt-hash-of-it");
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@example.com");
        assertThat(saved.getValue().getFullName()).isEqualTo("Ana Patient");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.email()).isEqualTo("ana@example.com");
    }

    @Test
    @DisplayName("a duplicate e-mail (the database's unique index fired) becomes a ConflictException")
    void duplicateEmailBecomesConflict() {
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.register(registration("long-enough-pw")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a password longer than 72 BYTES is refused even if it has fewer than 72 characters")
    void passwordOverBcryptLimitIsRefused() {
        // 40 characters, but each 'é' is 2 bytes in UTF-8, so 80 bytes: bcrypt would silently ignore the tail.
        String longInBytes = "é".repeat(40);

        assertThatThrownBy(() -> service.register(registration(longInBytes)))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("login with the right password returns a Bearer token")
    void loginSucceeds() {
        // A User built in a unit test has no id (the database assigns it), which is fine: JwtService is a mock.
        User user = new User("ana@example.com", "Ana", "stored-hash", UserRole.ORGANIZER);
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("right-pw", "stored-hash")).thenReturn(true);
        when(jwtService.issue(any(), eq(UserRole.ORGANIZER))).thenReturn(new JwtService.IssuedToken("jwt-value", 3600));

        LoginResponse response = service.login(new LoginRequest("ANA@example.com", "right-pw"));

        assertThat(response.accessToken()).isEqualTo("jwt-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(3600);
        assertThat(response.user().role()).isEqualTo(UserRole.ORGANIZER);
    }

    @Test
    @DisplayName("a wrong password is rejected and no token is issued")
    void wrongPasswordIsRejected() {
        User user = new User("ana@example.com", "Ana", "stored-hash", UserRole.CUSTOMER);
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("ana@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("an unknown e-mail still runs a full password comparison (against a dummy hash) and fails the same way")
    void unknownEmailBurnsTheSameTime() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        // The dummy hash is whatever encode() returned in the constructor: "hashed".
        when(passwordEncoder.matches(anyString(), eq("hashed"))).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "whatever-123")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        // THE POINT: matches() was called even though there is no account, so response time does not
        // reveal which e-mails exist.
        verify(passwordEncoder).matches("whatever-123", "hashed");
    }

    @Test
    @DisplayName("unknown e-mail and wrong password produce the SAME error message")
    void bothFailuresLookIdentical() {
        User user = new User("ana@example.com", "Ana", "stored-hash", UserRole.CUSTOMER);
        when(users.findByEmail("ana@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        String wrongPassword = catchMessage(() -> service.login(new LoginRequest("ana@example.com", "wrong")));
        String unknownEmail = catchMessage(() -> service.login(new LoginRequest("nobody@example.com", "wrong")));

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("getProfile answers NotFound when the account no longer exists")
    void profileOfDeletedUser() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(id)).isInstanceOf(NotFoundException.class);
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return expected.getMessage();
        }
        throw new AssertionError("expected an exception");
    }
}
