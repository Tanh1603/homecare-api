package com.homecare.identity.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 100) String firstName, @Size(max = 100) String lastName,
        @Size(max = 30) String phone, @Size(max = 500) String avatarUrl) {
}