package com.homecare.household.entity;

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
@Table(name = "care_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareRecord {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "owner_profile_id", nullable = false, length = 255)
    private String ownerProfileId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private String title;

    @Column(length = 2000)
    private String notes;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
