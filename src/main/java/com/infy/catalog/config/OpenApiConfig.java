package com.infy.catalog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 via springdoc. One group per API version (checklist): /v3/api-docs/v1.
 * Swagger UI at /swagger-ui.html, disabled in the prod profile.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Product Catalog API")
                        .version("1.0.0")
                        .description("CRUD, search and pagination for a product catalog. Every response is available "
                                + "as JSON, XML, HTML or plain text (Accept header or ?format=). Errors are RFC 9457 "
                                + "problem details with a stable errorCode and a traceId. Reads are public; writes "
                                + "need a JWT with scope catalog.write."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }

    @Bean
    GroupedOpenApi v1() {
        return GroupedOpenApi.builder().group("v1").pathsToMatch("/api/v1/**").build();
    }
}
