package com.portfolio.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * THE clock of the application. Code asks this bean "what time is it?" instead of calling {@code Instant.now()}
 * directly, so tests can swap in a frozen or movable clock (see src/test/.../support/TestClock). Rules such as
 * "a seat hold lasts 10 minutes" or "10 requests per minute" would otherwise be untestable without really waiting.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
