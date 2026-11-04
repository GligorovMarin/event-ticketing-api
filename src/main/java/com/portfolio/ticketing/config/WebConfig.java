package com.portfolio.ticketing.config;

import com.portfolio.ticketing.ratelimit.RateLimitInterceptor;
import com.portfolio.ticketing.ratelimit.SlidingWindowRateLimiter;
import com.portfolio.ticketing.security.AuthenticatedUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires extra behaviour into Spring MVC (the web layer):
 * <ul>
 *   <li>controllers may declare a parameter of type AuthenticatedUser (see security/AuthenticatedUser);</li>
 *   <li>methods annotated {@code @RateLimited} are limited (see ratelimit/).</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SlidingWindowRateLimiter rateLimiter;

    public WebConfig(SlidingWindowRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedUserArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter));
    }
}
