package com.homecare.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_devices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistrationEntity {

    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false, length = 255)
    private String recipientId;

    @Column(nullable = false)
    private String platform;

    @Column(name = "push_token", nullable = false, unique = true, length = 512)
    private String pushToken;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
