package com.ecommerce.user_service.services;

import com.ecommerce.user_service.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues and parses JWTs.
 * <p>
 * The secret and the {@link Clock} are constructor-injected: the key is built once (a secret
 * shorter than 32 bytes fails at startup with {@link io.jsonwebtoken.security.WeakKeyException}),
 * and tests can pass a fixed clock to mint already-expired tokens.
 */
@Service
public class JwtService {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(180);

    private final SecretKey secretKey;
    private final Clock clock;

    public JwtService(@Value("${jwt.secretKey}") String jwtSecretKey, Clock clock) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecretKey.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String generateAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                // TODO(follow-up): every user gets ROLE_ADMIN; derive roles from user.getRoles()
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(REFRESH_TOKEN_TTL)))
                .signWith(secretKey)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        return Long.valueOf(extractAllClaims(token).getSubject());
    }
}
