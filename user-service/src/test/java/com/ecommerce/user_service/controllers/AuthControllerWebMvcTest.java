package com.ecommerce.user_service.controllers;

import com.ecommerce.user_service.configs.WebSecurityConfig;
import com.ecommerce.user_service.dto.LoginResponseDTO;
import com.ecommerce.user_service.dto.SignUpResponseDTO;
import com.ecommerce.user_service.services.AuthService;
import com.ecommerce.user_service.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(WebSecurityConfig.class)
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private AuthService authService;

    @Test
    void signup_withInvalidEmail_is400() throws Exception {
        mockMvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Jane\", \"email\": \"not-an-email\", \"password\": \"s3cret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.apiError.subErrors", hasItem("Email must be in correct format.")));
        verifyNoInteractions(userService);
    }

    @Test
    void signup_withValidBody_isPublic_and201() throws Exception {
        SignUpResponseDTO response = new SignUpResponseDTO();
        response.setId("1");
        response.setEmail("jane@example.com");
        when(userService.signUp(any())).thenReturn(response);

        mockMvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Jane\", \"email\": \"jane@example.com\", \"password\": \"s3cret\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("jane@example.com"));
    }

    @Test
    void signup_duplicateEmail_is409_notA404() throws Exception {
        when(userService.signUp(any())).thenThrow(
                new com.ecommerce.user_service.exceptions.UserAlreadyExistsException("User with email jane@example.com already exists"));

        mockMvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Jane\", \"email\": \"jane@example.com\", \"password\": \"s3cret\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void login_badCredentials_is401() throws Exception {
        when(authService.login(any())).thenThrow(
                new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"jane@example.com\", \"password\": \"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_isPublic_andSetsHttpOnlyRefreshCookie() throws Exception {
        when(authService.login(any())).thenReturn(new LoginResponseDTO(1L, "access", "refresh"));

        mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"jane@example.com\", \"password\": \"s3cret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(cookie().value("refreshToken", "refresh"))
                .andExpect(cookie().httpOnly("refreshToken", true));
    }
}
