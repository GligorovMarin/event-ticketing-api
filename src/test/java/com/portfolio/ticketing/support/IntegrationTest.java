package com.portfolio.ticketing.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One annotation that sets up an integration test: put {@code @IntegrationTest} on a test class and you get
 * <ul>
 *   <li>the REAL application (all beans, all security rules), started once and shared by all test classes
 *       that use this annotation;</li>
 *   <li>the "test" profile, which points the datasource at the separate {@code ticketing_test} database
 *       (see application-test.yml);</li>
 *   <li>a {@code MockMvc}, a way to send HTTP requests to the application in memory, without opening a port.</li>
 * </ul>
 *
 * <p>Naming matters: classes ending in {@code IT} run in {@code mvn verify} (maven-failsafe-plugin, see pom.xml).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface IntegrationTest {
}
