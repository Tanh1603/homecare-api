package com.homecare.booking.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "booking_parts_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingPartsRequestEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "requested_by_profile_id", nullable = false, length = 255)
    private String requestedByProfileId;

    @Column(name = "part_name", nullable = false, length = 255)
    private String partName;

    @Column(name = "part_code", length = 120)
    private String partCode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "part_source", nullable = false, length = 32)
    private String partSource;

    @Column(name = "supplier_name", length = 255)
    private String supplierName;

    @Column(name = "cost_price")
    private Long costPrice;

    @Column(name = "unit_price")
    private Long unitPrice;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "compatibility_note", length = 1000)
    private String compatibilityNote;

    @Column(name = "refusal_reason", length = 1000)
    private String refusalReason;

    @Column(name = "evidence_ref", length = 1000)
    private String evidenceRef;

    @Column(name = "approved_by_profile_id", length = 255)
    private String approvedByProfileId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
