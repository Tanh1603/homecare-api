package com.homecare.household.entity;

import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "maintenance_policies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePolicyEntity {
    @Id
    private UUID id;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "owner_profile_id", length = 255)
    private String ownerProfileId;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(length = 255)
    private String model;

    @Column(nullable = false)
    private String title;

    @Column(name = "cadence_months", nullable = false)
    private Integer cadenceMonths;

    @Column(name = "policy_version", nullable = false)
    private Integer policyVersion;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "consent_granted", nullable = false)
    private boolean consentGranted;

    @Column(name = "first_due_on")
    private LocalDate firstDueOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
