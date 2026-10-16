package com.infy.catalog.config;

import com.infy.catalog.product.web.render.RepresentationConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.ITemplateEngine;

import java.util.List;

/**
 * MVC setup: the HTML and text converters (JSON and XML come from Spring Boot's Jackson setup),
 * and CORS configured once, from properties (checklist: explicit origins, never "*" with credentials).
 * Content negotiation (Accept header + ?format=) is configured in application.yml (spring.mvc.contentnegotiation).
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final ITemplateEngine templateEngine;

    public WebConfig(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new RepresentationConverters.Html(templateEngine));
        converters.add(new RepresentationConverters.Text());
    }

    /** Used by Spring Security (http.cors()). Only /api/** is cross-origin; no credentials. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CatalogProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Accept", "Content-Type", "If-Match", "If-None-Match", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("Location", "Link", "ETag", "X-Total-Count", "X-Correlation-Id"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
