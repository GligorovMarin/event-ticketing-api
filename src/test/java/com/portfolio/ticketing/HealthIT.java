package com.portfolio.ticketing;

import com.portfolio.ticketing.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole chain starts and answers: HTTP -> Spring Security -> Actuator -> PostgreSQL.
 * Also proves the "locked by default" rule from {@link com.portfolio.ticketing.config.SecurityConfig}.
 */
@IntegrationTest
class HealthIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /actuator/health is public and reports the database as UP")
    void healthIsPublicAndReportsDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("any other path is locked: anonymous callers get 401, not 403 and not 404")
    void everythingElseIsLockedByDefault() throws Exception {
        // This path does not exist at all. Security runs first, so the answer is 401 either way,
        // which proves that "forgetting to protect a new endpoint" cannot leave it open.
        mockMvc.perform(get("/some/endpoint/that/does/not/exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the Swagger UI page itself is public: /docs redirects to it, and the page loads")
    void swaggerUiPageIsPublic() throws Exception {
        // /docs is a shortcut that redirects to the real page...
        var redirect = mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");

        // ...and the real page (not just the JSON behind it) must be reachable without a login.
        mockMvc.perform(get(redirect))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Swagger UI's API description is public")
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Event Ticketing API"));
    }
}
