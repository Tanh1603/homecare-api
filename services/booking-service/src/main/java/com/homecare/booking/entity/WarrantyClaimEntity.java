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
@Table(name = "warranty_claims")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyClaimEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "household_warranty_id")
    private UUID householdWarrantyId;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "requester_profile_id", nullable = false, length = 255)
    private String requesterProfileId;

    @Column(name = "warranty_category", nullable = false, length = 32)
    private String warrantyCategory;

    @Column(name = "warranty_issuer", nullable = false, length = 24)
    private String warrantyIssuer;

    @Column(name = "reference_serial", length = 160)
    private String referenceSerial;

    @Column(name = "issue_description", nullable = false, length = 2000)
    private String issueDescription;

    @Column(name = "warranty_evidence_refs", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String warrantyEvidenceRefs = "[]";

    @Column(name = "authorization_evidence_refs", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String authorizationEvidenceRefs = "[]";

    @Column(name = "claim_status", nullable = false, length = 32)
    private String claimStatus;

    @Column(name = "warranty_verification_status", nullable = false, length = 24)
    @Builder.Default
    private String warrantyVerificationStatus = "UNVERIFIED";

    @Column(name = "authorization_verification_status", nullable = false, length = 24)
    @Builder.Default
    private String authorizationVerificationStatus = "UNVERIFIED";

    @Column(name = "validity_status", nullable = false, length = 24)
    @Builder.Default
    private String validityStatus = "ACTIVE";

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "provider_response_deadline_at")
    private Instant providerResponseDeadlineAt;

    @Column(name = "provider_no_response", nullable = false)
    @Builder.Default
    private boolean providerNoResponse = false;

    @Column(name = "rework_booking_id")
    private UUID reworkBookingId;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
