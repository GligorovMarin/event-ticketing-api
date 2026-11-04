package com.portfolio.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
 *   config/   security, OpenAPI documentation
 * </pre>
 */
@SpringBootApplication
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
