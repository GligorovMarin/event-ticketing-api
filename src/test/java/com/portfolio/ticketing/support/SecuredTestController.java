package com.portfolio.ticketing.support;

import com.portfolio.ticketing.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Three throwaway endpoints that exist ONLY in tests, so the role rules ({@code @PreAuthorize}) and the
 * {@link AuthenticatedUser} parameter can be tested on their own, before real endpoints use them.
 * {@code @Profile("test")} keeps it out of every other environment.
 */
@RestController
@RequestMapping("/test-support")
@Profile("test")
public class SecuredTestController {

    @GetMapping("/organizer-only")
    @PreAuthorize("hasRole('ORGANIZER')")
    public Map<String, String> organizerOnly(AuthenticatedUser user) {
        return Map.of("id", user.id().toString(), "role", user.role().name());
    }

    @GetMapping("/customer-only")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, String> customerOnly(AuthenticatedUser user) {
        return Map.of("id", user.id().toString(), "role", user.role().name());
    }

    @GetMapping("/any-user")
    public Map<String, String> anyLoggedInUser(AuthenticatedUser user) {
        return Map.of("id", user.id().toString(), "role", user.role().name());
    }
}
