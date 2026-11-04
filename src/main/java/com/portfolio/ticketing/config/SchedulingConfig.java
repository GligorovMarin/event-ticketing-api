package com.portfolio.ticketing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches Spring's {@code @Scheduled} support on (used by order/OrderExpirySweeper).
 *
 * <p>Controlled by the property {@code app.scheduling.enabled} (default: on). Tests switch it off
 * (application-test.yml) so no background job runs while a test is checking exact database contents; they run the
 * job explicitly instead, which also makes them deterministic.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
