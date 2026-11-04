package com.portfolio.ticketing.ratelimit;

import com.portfolio.ticketing.support.DatabaseCleaner;
import com.portfolio.ticketing.support.EventFixtures;
import com.portfolio.ticketing.support.EventFixtures.BuiltEvent;
import com.portfolio.ticketing.support.EventFixtures.SectionSpec;
import com.portfolio.ticketing.support.IntegrationTest;
import com.portfolio.ticketing.support.SeatFixtures;
import com.portfolio.ticketing.support.TestAccounts;
import com.portfolio.ticketing.support.TestAccounts.Account;
import com.portfolio.ticketing.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION test of the rate limit as users meet it: POST /orders allows 10 attempts per minute per customer.
 * Time is frozen with the {@link TestClock}, so every number below (Retry-After, window edges) is exact.
 */
@IntegrationTest
class RateLimitIT {

    private static final Instant T0 = Instant.parse("2026-12-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseCleaner cleaner;
    @Autowired
    private TestAccounts accounts;
    @Autowired
    private EventFixtures eventFixtures;
    @Autowired
    private SeatFixtures seatFixtures;
    @Autowired
    private TestClock clock;

    private Account ana;
    private Account boris;
    private BuiltEvent event;
    private UUID seatA1;
    private UUID seatA2;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        clock.freezeAt(T0);
        Account organizer = accounts.organizer("org@example.com");
        ana = accounts.customer("ana@example.com");
        boris = accounts.customer("boris@example.com");
        event = eventFixtures.published(organizer, "Rock Night", 30, new SectionSpec("Floor", 5000, 2, 6));
        seatA1 = seatFixtures.seatId(event.section("Floor"), "A1");
        seatA2 = seatFixtures.seatId(event.section("Floor"), "A2");
    }

    /** One attempt to order seat A2 as the given customer. */
    private ResultActions attempt(Account as, UUID seat) throws Exception {
        return mockMvc.perform(post("/orders")
                .header("Authorization", as.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"%s\",\"seatIds\":[\"%s\"]}".formatted(event.eventId(), seat)));
    }

    /** Uses up the whole allowance: attempt 1 creates the order, attempts 2 to 10 are refused with 409 (order in progress). */
    private void useUpTheAllowance(Account as) throws Exception {
        attempt(as, seatA1).andExpect(status().isCreated());
        for (int i = 2; i <= 10; i++) {
            attempt(as, seatA2).andExpect(status().isConflict());
        }
    }

    @Test
    @DisplayName("the 11th attempt within a minute is 429 with Retry-After, a problem document and the limit headers")
    void eleventhAttemptIsRefused() throws Exception {
        useUpTheAllowance(ana);

        attempt(ana, seatA2)
                .andExpect(status().isTooManyRequests())
                // All ten earlier attempts happened at T0, so the oldest leaves the window in a full 60 seconds.
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("every allowed response says how many attempts are left")
    void remainingIsReported() throws Exception {
        attempt(ana, seatA1).andExpect(header().string("X-RateLimit-Remaining", "9"));
        attempt(ana, seatA2).andExpect(header().string("X-RateLimit-Remaining", "8"));
    }

    @Test
    @DisplayName("failed attempts count too (here: 409s), otherwise a bot could hammer the endpoint for free")
    void failedAttemptsCount() throws Exception {
        useUpTheAllowance(ana); // 1 success + 9 conflicts = 10 attempts

        attempt(ana, seatA2).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the limit is per customer: Boris is not affected by Ana's 429")
    void perCustomer() throws Exception {
        useUpTheAllowance(ana);
        attempt(ana, seatA2).andExpect(status().isTooManyRequests());

        attempt(boris, seatFixtures.seatId(event.section("Floor"), "B1")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the window slides: the Retry-After shrinks as time passes, and the exact end of the window frees a slot")
    void windowSlides() throws Exception {
        useUpTheAllowance(ana);

        clock.advance(Duration.ofSeconds(30));
        attempt(ana, seatA2).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "30"));

        clock.advance(Duration.ofSeconds(29));   // T0 + 59 s
        attempt(ana, seatA2).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));

        clock.advance(Duration.ofSeconds(1));    // T0 + 60 s: the ten T0 attempts are exactly one window old
        attempt(ana, seatA2).andExpect(status().isConflict()); // allowed through the limiter; "order in progress" is the business answer
    }

    @Test
    @DisplayName("other endpoints are not limited: 30 reads in a row are fine")
    void otherEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/orders").header("Authorization", ana.bearer())).andExpect(status().isOk());
        }
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/events")).andExpect(status().isOk());
        }
    }
}
