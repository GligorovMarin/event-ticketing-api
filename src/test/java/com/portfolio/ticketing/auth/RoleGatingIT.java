package com.portfolio.ticketing.auth;

import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of the role rules, using the test-only endpoints in support/SecuredTestController.
 *
 * <p>401 and 403 mean different things and the difference matters:
 * <ul>
 *   <li><b>401 Unauthorized</b>: "I do not know who you are" (no or bad token);</li>
 *   <li><b>403 Forbidden</b>: "I know who you are, and the answer is no" (valid token, wrong role).</li>
 * </ul>
 */
@IntegrationTest
class RoleGatingIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account customer;
    private TestAccounts.Account organizer;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        customer = accounts.customer("ana@example.com");
        organizer = accounts.organizer("org@example.com");
    }

    @Test
    @DisplayName("an organizer-only endpoint: organizer 200, customer 403, anonymous 401")
    void organizerOnly() throws Exception {
        mockMvc.perform(get("/test-support/organizer-only").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test-support/organizer-only").header("Authorization", customer.bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/test-support/organizer-only"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a customer-only endpoint: customer 200, organizer 403, anonymous 401")
    void customerOnly() throws Exception {
        mockMvc.perform(get("/test-support/customer-only").header("Authorization", customer.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test-support/customer-only").header("Authorization", organizer.bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/test-support/customer-only"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an endpoint with no role rule accepts any logged-in user, but not anonymous callers")
    void anyLoggedInUser() throws Exception {
        mockMvc.perform(get("/test-support/any-user").header("Authorization", customer.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test-support/any-user").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test-support/any-user"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a 403 is a JSON problem document, like every other error")
    void forbiddenHasTheStandardShape() throws Exception {
        mockMvc.perform(get("/test-support/organizer-only").header("Authorization", customer.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("controllers receive the caller as an AuthenticatedUser built from the token")
    void authenticatedUserParameter() throws Exception {
        mockMvc.perform(get("/test-support/any-user").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(organizer.id().toString()))
                .andExpect(jsonPath("$.role").value("ORGANIZER"));
    }

    @Test
    @DisplayName("the AuthenticatedUser parameter does not leak into the API documentation as something to send")
    void authenticatedUserIsHiddenFromTheDocs() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // /auth/me declares an AuthenticatedUser parameter; it must NOT show up as a request parameter.
                .andExpect(jsonPath("$.paths['/auth/me'].get.parameters").doesNotExist());
    }
}
