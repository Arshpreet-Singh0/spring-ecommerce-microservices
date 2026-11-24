package com.ecommerce.product_service.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.List;

/**
 * Parses and verifies access tokens issued by user-service.
 * <p>
 * The key is built once in the constructor, so a secret shorter than 32 bytes fails at startup
 * ({@link io.jsonwebtoken.security.WeakKeyException}) instead of on the first request.
 * Expiry is checked against the injected {@link Clock}, which keeps the class unit-testable.
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final Clock clock;

    public JwtService(@Value("${jwt.secretKey}") String jwtSecretKey, Clock clock) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecretKey.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.valueOf(extractAllClaims(token).getSubject());
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return extractAllClaims(token).get("roles", List.class);
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
