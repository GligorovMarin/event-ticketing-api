package com.portfolio.ticketing.config;

import com.portfolio.ticketing.security.AuthenticatedUserArgumentResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;

/**
 * Small web-layer wiring that does not belong anywhere else.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Lets controllers declare a parameter of type AuthenticatedUser. See security/AuthenticatedUser. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedUserArgumentResolver());
    }

    /**
     * THE clock of the application. Code asks this bean "what time is it?" instead of calling
     * {@code Instant.now()} directly, so tests can swap in a frozen or movable clock. Rules such as
     * "a seat hold lasts 10 minutes" would otherwise be untestable without really waiting.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
