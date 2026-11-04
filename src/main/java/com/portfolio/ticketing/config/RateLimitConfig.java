package com.portfolio.ticketing.config;

import com.portfolio.ticketing.ratelimit.SlidingWindowRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One rate limiter for the whole application (it keeps the per-user request counters in memory).
 * The {@link Clock} is INJECTED, so whichever clock the application uses, the limiter uses the same one.
 * See ratelimit/ for the algorithm and config/WebConfig for where it is applied.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    SlidingWindowRateLimiter rateLimiter(Clock clock) {
        return new SlidingWindowRateLimiter(clock);
    }
}
