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
@Table(name = "maintenance_schedules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceSchedule {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "owner_profile_id", nullable = false, length = 255)
    private String ownerProfileId;

    @Column(nullable = false)
    private String title;

    @Column(name = "cadence_months")
    private Integer cadenceMonths;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false)
    private String status;

    @Column(name = "reminder_enabled", nullable = false)
    @Builder.Default
    private boolean reminderEnabled = true;

    @Column(name = "last_skipped_at")
    private Instant lastSkippedAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_serviced_at")
    private Instant lastServicedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
