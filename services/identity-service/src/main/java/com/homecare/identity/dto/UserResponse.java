package com.homecare.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.homecare.identity.domain.UserProfile;

public record UserResponse(UUID id, String username, String email, String firstName, String lastName,
        String phone, String avatarUrl, String status, Instant lastLoginAt, Instant createdAt) {
    public static UserResponse from(UserProfile u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getPhone(), u.getAvatarUrl(), u.getStatus().name(), u.getLastLoginAt(), u.getCreatedAt());
    }
}