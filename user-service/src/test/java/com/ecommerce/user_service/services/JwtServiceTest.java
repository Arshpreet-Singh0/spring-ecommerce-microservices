package com.ecommerce.user_service.services;

import com.ecommerce.user_service.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private final JwtService jwtService = new JwtService(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validAccessToken_roundTripsUserIdAndClaims() {
        String token = jwtService.generateAccessToken(user());

        Claims claims = jwtService.extractAllClaims(token);
        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(5L);
        assertThat(claims.getSubject()).isEqualTo("5");
        assertThat(claims.get("email", String.class)).isEqualTo("jane@example.com");
        assertThat(claims.get("roles", java.util.List.class)).contains("ROLE_USER");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(JwtService.ACCESS_TOKEN_TTL));
    }

    @Test
    void refreshToken_carriesOnlyTheSubject_andLivesLonger() {
        String token = jwtService.generateRefreshToken(user());

        Claims claims = jwtService.extractAllClaims(token);
        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(5L);
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(JwtService.REFRESH_TOKEN_TTL));
    }

    @Test
    void expiredToken_isRejected() {
        // minted 16 minutes ago -> the 15-minute access token has expired
        JwtService issuedInThePast = new JwtService(SECRET,
                Clock.fixed(NOW.minus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        String token = issuedInThePast.generateAccessToken(user());

        assertThatThrownBy(() -> jwtService.extractAllClaims(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedPayload_failsSignatureCheck() {
        String token = jwtService.generateAccessToken(user());
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        int i = payload.length / 2;
        payload[i] = payload[i] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> jwtService.extractAllClaims(tampered)).isInstanceOf(SignatureException.class);
    }

    @Test
    void tokenSignedWithAnotherKey_failsSignatureCheck() {
        JwtService otherIssuer = new JwtService("a-different-secret-key-also-at-least-32-bytes",
                Clock.fixed(NOW, ZoneOffset.UTC));
        String foreign = otherIssuer.generateAccessToken(user());

        assertThatThrownBy(() -> jwtService.extractAllClaims(foreign)).isInstanceOf(SignatureException.class);
    }

    @Test
    void secretShorterThan32Bytes_failsFastAtConstruction() {
        // Documents the Task 2 gotcha: HS256 needs >= 256-bit keys, so a short JWT_SECRET breaks startup.
        assertThatThrownBy(() -> new JwtService("too-short-secret", Clock.systemUTC()))
                .isInstanceOf(WeakKeyException.class);
    }

    private static User user() {
        User user = new User();
        user.setId(5L);
        user.setEmail("jane@example.com");
        return user;
    }
}
