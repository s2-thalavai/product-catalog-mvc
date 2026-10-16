package com.infy.catalog.common;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** HS256 tokens signed with the dev secret, like scripts/dev-token.sh. */
public final class TestTokens {

    public static final String DEV_SECRET = "local-dev-only-secret-change-me-0123456789";

    private TestTokens() {
    }

    public static String withScope(String scope) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("integration-test")
                    .issuer("catalog-dev")
                    .claim("scope", scope)
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
