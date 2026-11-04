package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.auth.dto.LoginRequest;
import com.portfolio.ticketing.auth.dto.LoginResponse;
import com.portfolio.ticketing.auth.dto.RegisterRequest;
import com.portfolio.ticketing.auth.dto.UserResponse;
import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP layer for accounts: it maps URLs to {@link AuthService} calls and contains no business logic.
 *
 * <pre>
 *   POST /auth/register   PUBLIC     create an account (customer or organizer)
 *   POST /auth/login      PUBLIC     e-mail + password -> access token (JWT)
 *   GET  /auth/me         logged in  who does my token say I am?
 * </pre>
 * Which paths are public is decided in config/SecurityConfig, not here.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account", description = "role is CUSTOMER or ORGANIZER")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive an access token")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Return the currently logged-in user")
    public UserResponse me(AuthenticatedUser user) {
        return authService.getProfile(user.id());
    }
}
