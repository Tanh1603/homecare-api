package com.homecare.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.identity.dto.AuthDto.ForgotPasswordRequest;
import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.LogoutRequest;
import com.homecare.identity.dto.AuthDto.RefreshTokenRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.dto.AuthDto.SendVerificationEmailRequest;
import com.homecare.identity.dto.ApiMessageResponse;
import com.homecare.identity.dto.AuthDto.TokenResponse;
import com.homecare.identity.dto.LoginResponse;
import com.homecare.identity.dto.UserProfileResponse;
import com.homecare.identity.mapper.UserProfileMapper;
import com.homecare.identity.service.AuthService;
import com.homecare.identity.web.ApiMessageFactory;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserProfileMapper userProfileMapper;
    private final ApiMessageFactory messageFactory;

    @PostMapping("/register")
    public ResponseEntity<UserProfileResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userProfileMapper.toResponse(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiMessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(messageFactory.create("success.forgot-password.accepted"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse token = authService.refreshToken(request);
        return ResponseEntity.ok(token);
    }

    @PostMapping("/send-verification-email")
    public ResponseEntity<ApiMessageResponse> sendVerificationEmail(
            @Valid @RequestBody SendVerificationEmailRequest request) {
        authService.sendVerificationEmail(request.getEmail());
        return ResponseEntity.ok(messageFactory.create("success.email-verification.sent"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiMessageResponse> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(messageFactory.create("success.logout.succeeded"));
    }
}