package com.portfolio.ticketing.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Applies {@link RateLimited} to controller methods.
 *
 * <p>An <b>interceptor</b> runs just before a controller method, after the security filters, so the caller's
 * identity is already known. If the method carries {@code @RateLimited}, we ask the
 * {@link SlidingWindowRateLimiter}; a refusal throws {@link TooManyRequestsException}, which
 * common/ApiExceptionHandler turns into a 429 response. Methods without the annotation are untouched.
 *
 * <p>The limit is per USER and per ENDPOINT: the key is "userId:Controller#method". Anonymous callers are not
 * limited here because they cannot reach rate-limited endpoints (security answers 401 first).
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SlidingWindowRateLimiter limiter;

    public RateLimitInterceptor(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // static resources and the like
        }

        RateLimited rule = handlerMethod.getMethodAnnotation(RateLimited.class);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (rule == null || authentication == null || !authentication.isAuthenticated()) {
            return true;
        }

        String key = authentication.getName() + ":" + handlerMethod.getBeanType().getSimpleName()
                + "#" + handlerMethod.getMethod().getName();
        SlidingWindowRateLimiter.Decision decision =
                limiter.tryAcquire(key, rule.limit(), Duration.ofSeconds(rule.windowSeconds()));

        // Tell well-behaved clients where they stand, even when the request is allowed.
        response.setHeader("X-RateLimit-Limit", String.valueOf(rule.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            // Round UP: telling a client "0 seconds" would invite an immediate, pointless retry.
            long retryAfterSeconds = (decision.retryAfter().toMillis() + 999) / 1000;
            throw new TooManyRequestsException(retryAfterSeconds);
        }
        return true;
    }
}
