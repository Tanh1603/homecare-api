package com.homecare.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.identity.dto.ApiResponse;
import com.homecare.identity.dto.AuthDto.ForgotPasswordRequest;
import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.LogoutRequest;
import com.homecare.identity.dto.AuthDto.RefreshTokenRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.dto.AuthDto.SendVerificationEmailRequest;
import com.homecare.identity.dto.AuthDto.TokenResponse;
import com.homecare.identity.dto.LoginResponse;
import com.homecare.identity.dto.UserProfileResponse;
import com.homecare.identity.mapper.UserProfileMapper;
import com.homecare.identity.service.AuthService;
import com.homecare.identity.web.ApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserProfileMapper userProfileMapper;
    private final ApiResponseFactory responseFactory;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserProfileResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        UserProfileResponse profile = userProfileMapper.toResponse(authService.register(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.success(
                        HttpStatus.CREATED,
                        "success.registration.created",
                        profile,
                        httpRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "login.succeeded",
                authService.login(request),
                httpRequest));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.forgot-password.accepted",
                null,
                httpRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest) {
        TokenResponse token = authService.refreshToken(request);
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.token.refreshed",
                token,
                httpRequest));
    }

    @PostMapping("/send-verification-email")
    public ResponseEntity<ApiResponse<Void>> sendVerificationEmail(
            @Valid @RequestBody SendVerificationEmailRequest request,
            HttpServletRequest httpRequest) {
        authService.sendVerificationEmail(request.getEmail());
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.email-verification.sent",
                null,
                httpRequest));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody LogoutRequest request,
            HttpServletRequest httpRequest) {
        authService.logout(request);
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.logout.succeeded",
                null,
                httpRequest));
    }
}