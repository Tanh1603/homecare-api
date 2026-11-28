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
@Table(name = "warranty_commitment_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyCommitmentSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "quotation_id")
    private UUID quotationId;

    @Column(name = "quotation_version", nullable = false)
    @Builder.Default
    private int quotationVersion = 0;

    @Column(name = "service_category", nullable = false, length = 64)
    private String serviceCategory;

    @Column(name = "workmanship_duration_days", nullable = false)
    private int workmanshipDurationDays;

    @Column(name = "committed_parts_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String committedPartsJson = "[]";

    @Column(name = "policy_summary", length = 1000)
    private String policySummary;

    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @Column(name = "committed_by_profile_id", nullable = false, length = 255)
    private String committedByProfileId;
}
