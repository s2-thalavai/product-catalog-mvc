package com.infy.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Typed application settings (prefix {@code catalog}). Validated at startup so a bad
 * deployment fails fast instead of misbehaving later.
 *
 * @param seedData load the demo catalog (Flyway location classpath:db/seed)
 * @param cors     browser origins allowed to call the API
 * @param security JWT settings
 */
@ConfigurationProperties(prefix = "catalog")
public record CatalogProperties(
        @DefaultValue("false") boolean seedData,
        @DefaultValue Cors cors,
        @DefaultValue Security security) {

    /** Explicit origins only. "*" is rejected (checklist: never a wildcard). */
    public record Cors(@DefaultValue List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty())
                            .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o).toList();
            for (String origin : allowedOrigins) {
                if (origin.contains("*") || !(origin.startsWith("http://") || origin.startsWith("https://"))) {
                    throw new IllegalArgumentException("catalog.cors.allowed-origins must list explicit origins like "
                            + "https://shop.example.com (no wildcards), but contained '" + origin + "'");
                }
            }
        }
    }

    public record Security(@DefaultValue Jwt jwt) {
    }

    /**
     * @param hmacSecret shared secret for HS256 tokens (local / k3s only). Leave empty in production,
     *                   where tokens are validated against the identity provider
     *                   (spring.security.oauth2.resourceserver.jwt.issuer-uri).
     */
    public record Jwt(String hmacSecret) {
        public Jwt {
            if (hmacSecret != null && !hmacSecret.isBlank() && hmacSecret.length() < 32) {
                throw new IllegalArgumentException("catalog.security.jwt.hmac-secret must be at least 32 characters (HS256)");
            }
            hmacSecret = hmacSecret == null || hmacSecret.isBlank() ? null : hmacSecret;
        }
    }
}
