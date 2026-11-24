package com.ecommerce.user_service.services;

import com.ecommerce.user_service.dto.LoginDTO;
import com.ecommerce.user_service.dto.LoginResponseDTO;
import com.ecommerce.user_service.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserService userService;

    private final JwtService jwtService =
            new JwtService("test-secret-key-that-is-at-least-32-bytes-long", Clock.systemUTC());

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(authenticationManager, jwtService, userService);
    }

    @Test
    void login_returnsAccessAndRefreshTokens_forTheAuthenticatedUser() {
        User user = user();
        when(authenticationManager.authenticate(argThat(a ->
                "jane@example.com".equals(a.getPrincipal()) && "s3cret".equals(a.getCredentials()))))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, List.of()));

        LoginResponseDTO response = authService.login(login("jane@example.com", "s3cret"));

        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank().isNotEqualTo(response.getAccessToken());
        assertThat(jwtService.getUserIdFromToken(response.getAccessToken())).isEqualTo(5L);
        assertThat(jwtService.getUserIdFromToken(response.getRefreshToken())).isEqualTo(5L);
    }

    @Test
    void login_badCredentials_propagate() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(login("jane@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refreshToken_issuesNewAccessToken_andKeepsTheRefreshToken() {
        User user = user();
        String refresh = jwtService.generateRefreshToken(user);
        when(userService.findById(5L)).thenReturn(user);

        LoginResponseDTO response = authService.refreshToken(refresh);

        assertThat(response.getRefreshToken()).isEqualTo(refresh);
        assertThat(jwtService.extractAllClaims(response.getAccessToken()).get("email")).isEqualTo("jane@example.com");
    }

    private static User user() {
        User user = new User();
        user.setId(5L);
        user.setEmail("jane@example.com");
        return user;
    }

    private static LoginDTO login(String email, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }
}
