package com.homecare.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDto {

    // Request đăng ký (US-HU-001)
    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Username không được để trống")
        private String username;

        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        private String email;

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 6, message = "Mật khẩu phải từ 6 ký tự trở lên")
        private String password;

        @NotBlank(message = "Họ tên không được để trống")
        private String firstName;

        @NotBlank(message = "Tên đệm không được để trống")
        private String lastName;

        private String phone;
        private String address;
    }

    // Request đăng nhập
    @Data
    public static class LoginRequest {
        @NotBlank(message = "Email không được để trống")
        private String email;

        @NotBlank(message = "Mật khẩu không được để trống")
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
        @NotBlank(message = "Họ tên không được để trống")
        private String firstName;
        @NotBlank(message = "Tên đệm không được để trống")
        private String lastName;
        private String phone;
        private String address;
    }

    @Data
    public static class RefreshTokenRequest {
        @NotBlank(message = "Refresh token không được để trống")
        private String refreshToken;
    }

    // Request Đổi mật khẩu
    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "Mật khẩu hiện tại không được để trống")
        private String currentPassword;

        @NotBlank(message = "Mật khẩu mới không được để trống")
        @Size(min = 6, message = "Mật khẩu mới phải có ít nhất 6 ký tự")
        private String newPassword;
    }

    // Request Quên mật khẩu
    @Data
    public static class ForgotPasswordRequest {
        @NotBlank(message = "Email không được để trống")
        @Email
        private String email;
    }

    @Data
    public static class LogoutRequest {
        @NotBlank(message = "Refresh token không được để trống")
        private String refreshToken;
    }
}