package com.homecare.booking.entity;

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
@Table(name = "warranty_activation_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyActivationSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "commitment_snapshot_id")
    private UUID commitmentSnapshotId;

    @Column(name = "workmanship_duration_days", nullable = false)
    private int workmanshipDurationDays;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "actual_parts_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String actualPartsJson = "[]";

    @Column(name = "excluded_scope_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String excludedScopeJson = "[]";

    @Column(name = "material_discrepancy", nullable = false)
    @Builder.Default
    private boolean materialDiscrepancy = false;

    @Column(name = "discrepancy_summary", length = 1500)
    private String discrepancySummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
