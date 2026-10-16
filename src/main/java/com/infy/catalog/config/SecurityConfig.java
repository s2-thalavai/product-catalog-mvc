package com.infy.catalog.config;

import com.infy.catalog.common.error.ProblemAuthenticationHandlers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * OAuth2 resource server (JWT bearer tokens), stateless.
 * <ul>
 *   <li>Reading the catalog is public: GET/HEAD anywhere, plus health and API docs.</li>
 *   <li>Writes on /api/v1/products need scope {@code catalog.write} (authority SCOPE_catalog.write).</li>
 *   <li>Everything else needs an authenticated caller.</li>
 * </ul>
 * Tokens: production validates against the identity provider (issuer-uri, e.g. Entra ID / Keycloak);
 * local and k3s use an HS256 shared secret (catalog.security.jwt.hmac-secret, see scripts/dev-token.sh).
 */
@Configuration
public class SecurityConfig {

    public static final String WRITE_AUTHORITY = "SCOPE_catalog.write";
    private static final String PRODUCTS = "/api/v1/products";

    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemAuthenticationHandlers problems) throws Exception {
        http
                // Stateless bearer-token API: no session, no cookies, so no CSRF exposure.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PRODUCTS).hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, PRODUCTS + "/**").hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.PATCH, PRODUCTS + "/**").hasAuthority(WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.DELETE, PRODUCTS + "/**").hasAuthority(WRITE_AUTHORITY)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                // Strict CSP for our HTML pages; Swagger UI (local only) needs its scripts.
                .headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(new OrRequestMatcher(
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/swagger-ui.html"))),
                        new StaticHeadersWriter("Content-Security-Policy", CONTENT_SECURITY_POLICY))));
        return http.build();
    }

    /** Local / k3s: HS256 tokens signed with a shared secret. Production leaves the secret empty. */
    @Bean
    @ConditionalOnExpression("'${catalog.security.jwt.hmac-secret:}' != ''")
    JwtDecoder hmacJwtDecoder(CatalogProperties properties) {
        byte[] key = properties.security().jwt().hmacSecret().getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
