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
@Table(name = "booking_parts_usage")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingPartsUsageEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "parts_request_id")
    private UUID partsRequestId;

    @Column(name = "part_name", nullable = false, length = 255)
    private String partName;

    @Column(name = "part_code", length = 120)
    private String partCode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "part_source", nullable = false, length = 32)
    private String partSource;

    @Column(name = "unit_price", nullable = false)
    @Builder.Default
    private long unitPrice = 0L;

    @Column(name = "parts_warranty_status", nullable = false, length = 24)
    private String partsWarrantyStatus;

    @Column(name = "warranty_issuer", length = 24)
    private String warrantyIssuer;

    @Column(name = "warranty_duration_days")
    private Integer warrantyDurationDays;

    @Column(name = "warranty_terms", length = 1000)
    private String warrantyTerms;

    @Column(name = "evidence_ref", length = 1000)
    private String evidenceRef;

    @Column(name = "purchase_proof_ref", length = 1000)
    private String purchaseProofRef;

    @Column(name = "recorded_by_profile_id", nullable = false, length = 255)
    private String recordedByProfileId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
