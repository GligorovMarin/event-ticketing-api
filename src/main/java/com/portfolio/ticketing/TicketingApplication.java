package com.portfolio.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The entry point: {@code mvn spring-boot:run} (see the Dockerfile) starts this class.
 *
 * <p>{@link SpringBootApplication} switches on three things at once:
 * <ul>
 *   <li><b>component scanning</b>: Spring finds every class annotated with @Service, @RestController,
 *       @Configuration ... in this package and all packages below it, and creates one instance of each;</li>
 *   <li><b>auto-configuration</b>: Spring Boot looks at the libraries on the classpath (PostgreSQL driver,
 *       Flyway, Spring Security ...) and configures them using the settings in application.yml;</li>
 *   <li><b>configuration</b>: this class can itself declare beans.</li>
 * </ul>
 *
 * <p>Where things live (packages below this one are added milestone by milestone):
 * <pre>
 *   config/    security rules, web wiring, OpenAPI documentation
 *   security/  the AuthenticatedUser that controllers receive
 *   auth/      register, login, JWT creation
 *   user/      the users table (entity + repository)
 *   event/     events, sections (priced seat blocks) and seats; organizer writes, public browsing
 *   availability/  seat map with live status, and the "find N seats together" algorithm
 *   order/     the business rules of buying (limits, hold duration); orders themselves come next
 *   common/    shared exceptions and the one place that turns them into HTTP responses
 * </pre>
 */
@SpringBootApplication
// Finds classes annotated @ConfigurationProperties (like auth/JwtProperties) and fills them from application.yml.
@ConfigurationPropertiesScan
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
