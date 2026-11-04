package com.homecare.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AuthDto {

    // Request đăng ký (US-HU-001)
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

    // Request đăng nhập
    @Data
    public static class LoginRequest {
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        private String email;

        @NotBlank(message = "{validation.password.required}")
        private String password;
    }

    // Response Token
    @Data
    public static class TokenResponse {
        private String accessToken;
        private String refreshToken;
        private Long expiresIn;
        private String tokenType;
    }

    // Request cập nhật Profile (US-HU-002)
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

    // Request Đổi mật khẩu
    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "{validation.current-password.required}")
        private String currentPassword;

        @NotBlank(message = "{validation.new-password.required}")
        @Size(min = 6, message = "{validation.new-password.too-short}")
        private String newPassword;
    }

    // Request Quên mật khẩu
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