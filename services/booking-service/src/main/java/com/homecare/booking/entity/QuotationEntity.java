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
@Table(name = "quotations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotationEntity {

    @Id
    private UUID id;

    @Column(name = "service_request_id")
    private UUID serviceRequestId;

    @Column(name = "quote_kind", nullable = false, length = 20)
    @Builder.Default
    private String quoteKind = "INITIAL";

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String currency = "VND";

    @Column(name = "items_total", nullable = false)
    private long itemsTotal;

    @Column(name = "inspection_fee_paid", nullable = false)
    @Builder.Default
    private long inspectionFeePaid = 0L;

    @Column(name = "inspection_fee_credit", nullable = false)
    @Builder.Default
    private long inspectionFeeCredit = 0L;

    @Column(name = "total_due_after_credit", nullable = false)
    private long totalDueAfterCredit;

    @Column(name = "policy_version", nullable = false, length = 64)
    @Builder.Default
    private String policyVersion = "v1";

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "repair_booking_id")
    private UUID repairBookingId;

    @Column(name = "supersede_reason", length = 500)
    private String supersedeReason;

    @Column(name = "revision_reason", length = 500)
    private String revisionReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
