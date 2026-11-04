package com.portfolio.ticketing.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on a controller method to limit how often ONE user may call it:
 * <pre>{@code
 *   @RateLimited(limit = 10, windowSeconds = 60)   // at most 10 calls per minute per user
 *   @PostMapping
 *   OrderResponse create(...)
 * }</pre>
 * Enforced by {@link RateLimitInterceptor} (registered in config/WebConfig). Over the limit the caller gets
 * {@code 429 Too Many Requests} with a {@code Retry-After} header.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    int limit();

    int windowSeconds();
}
