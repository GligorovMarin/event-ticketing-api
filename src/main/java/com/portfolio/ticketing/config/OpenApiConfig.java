package com.portfolio.ticketing.config;

import com.portfolio.ticketing.security.AuthenticatedUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * The text and the "Authorize" button of Swagger UI (http://localhost:8080/docs).
 *
 * <p>springdoc reads our controllers and builds the API description automatically; this class only
 * adds the title and tells Swagger UI that protected endpoints expect a bearer token (the JWT).
 */
@Configuration
public class OpenApiConfig {

    static {
        // Controllers declare a parameter of type AuthenticatedUser to receive the caller; it is filled from the
        // token, NOT from the request, so it must not appear in the documentation as something to send.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthenticatedUser.class);
    }

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI ticketingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Ticketing API")
                        .description("Events, seat maps, timed seat holds and orders")
                        .version("0.1.0"))
                // Declares HOW to authenticate: "Authorization: Bearer <token>"...
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // ...and applies it to every endpoint, which adds the padlock and Authorize button.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
