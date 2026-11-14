package com.pricingsim.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;

/** springdoc metadata: {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html} (PLAN.md §13). */
@Configuration
@Profile("!offline")
public class OpenApiConfig {

    @Bean
    OpenAPI pricingSimOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Pricing & Promotion Simulator API").version("v1")
                        .description("Simulate a price with the pricing engine (read-only), explain stored decisions,"
                                + " and query detected exceptions over the loaded synthetic dataset. Errors are RFC 7807"
                                + " ProblemDetail. Ground-truth labels are never exposed.")
                        .license(new License().name("MIT")))
                .tags(List.of(
                        new Tag().name("Pricing").description("What-if pricing with the engine"),
                        new Tag().name("Decisions").description("Stored decisions and their traceability chain"),
                        new Tag().name("Exceptions").description("Detected anomalies and operational exceptions"),
                        new Tag().name("Products").description("Daily price history"),
                        new Tag().name("Reports").description("Latest validation and evaluation results")));
    }
}
