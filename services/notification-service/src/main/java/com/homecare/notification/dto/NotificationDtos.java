package com.homecare.notification.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(
            UUID id,
            String recipientId,
            String eventType,
            String title,
            String body,
            boolean read,
            Instant readAt,
            Instant createdAt) {
    }

    public record RegisterDeviceRequest(
            @NotBlank String platform,
            @NotBlank String pushToken) {
    }

    public record DeviceRegistrationResponse(
            UUID id,
            String recipientId,
            String platform,
            String pushToken,
            boolean active,
            Instant createdAt) {
    }
}
