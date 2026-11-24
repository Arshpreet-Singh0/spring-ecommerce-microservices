package com.ecommerce.user_service.services;

import com.ecommerce.user_service.dto.SignUpDTO;
import com.ecommerce.user_service.dto.SignUpResponseDTO;
import com.ecommerce.user_service.entities.User;
import com.ecommerce.user_service.exceptions.UserAlreadyExistsException;
import com.ecommerce.user_service.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new ModelMapper(), passwordEncoder);
    }

    @Test
    void signUp_storesEncodedPassword_andReturnsUserWithoutPassword() {
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("s3cret")).thenReturn("{bcrypt}hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        SignUpResponseDTO response = userService.signUp(signUp("Jane", "jane@example.com", "s3cret"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("{bcrypt}hash");
        assertThat(saved.getValue().getEmail()).isEqualTo("jane@example.com");
        assertThat(response.getId()).isEqualTo("1");
        assertThat(response.getEmail()).isEqualTo("jane@example.com");
        assertThat(response.getName()).isEqualTo("Jane");
    }

    @Test
    void signUp_duplicateEmail_isRejected_andNothingSaved() {
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.signUp(signUp("Jane", "jane@example.com", "x")))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessageContaining("jane@example.com");
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void findById_unknownUser_throwsBadCredentials() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(9L)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loadUserByUsername_findsByEmail_orThrows() {
        User user = new User();
        user.setEmail("jane@example.com");
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(userService.loadUserByUsername("jane@example.com")).isSameAs(user);
        assertThatThrownBy(() -> userService.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static SignUpDTO signUp(String name, String email, String password) {
        SignUpDTO dto = new SignUpDTO();
        dto.setName(name);
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }
}
