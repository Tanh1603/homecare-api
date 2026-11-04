package com.homecare.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        String phone,
        String address,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
