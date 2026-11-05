package com.homecare.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AuthDto {

    // Registration request.
    @Data
    public static class RegisterRequest {
        @NotBlank(message = "{validation.username.required}")
        private String username;

        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        private String email;

        @NotBlank(message = "{validation.password.required}")
        @Size(min = 6, message = "{validation.password.too-short}")
        private String password;

        @NotBlank(message = "{validation.first-name.required}")
        private String firstName;

        @NotBlank(message = "{validation.last-name.required}")
        private String lastName;

        private String phone;
        private String address;
    }

    // Login request.
    @Data
    public static class LoginRequest {
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        private String email;

        @NotBlank(message = "{validation.password.required}")
        private String password;
    }

    // Token response.
    @Data
    public static class TokenResponse {
        private String accessToken;
        private String refreshToken;
        private Long expiresIn;
        private String tokenType;
    }

    // Profile update request.
    @Data
    public static class UpdateProfileRequest {
        @NotBlank(message = "{validation.first-name.required}")
        private String firstName;
        @NotBlank(message = "{validation.last-name.required}")
        private String lastName;
        private String phone;
        private String address;
    }

    @Data
    public static class RefreshTokenRequest {
        @NotBlank(message = "{validation.refresh-token.required}")
        private String refreshToken;
    }

    // Password change request.
    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "{validation.current-password.required}")
        private String currentPassword;

        @NotBlank(message = "{validation.new-password.required}")
        @Size(min = 6, message = "{validation.new-password.too-short}")
        private String newPassword;
    }

    // Password reset request.
    @Data
    public static class ForgotPasswordRequest {
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        private String email;
    }

    @Data
    public static class SendVerificationEmailRequest {
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        private String email;
    }

    @Data
    public static class LogoutRequest {
        @NotBlank(message = "{validation.refresh-token.required}")
        private String refreshToken;
    }
}
