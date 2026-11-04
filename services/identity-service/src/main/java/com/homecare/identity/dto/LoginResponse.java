package com.homecare.identity.dto;

import com.homecare.identity.dto.AuthDto.TokenResponse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private boolean requiresEmailVerification;
    private String email;
    private TokenResponse tokens;
    private String message;
}
