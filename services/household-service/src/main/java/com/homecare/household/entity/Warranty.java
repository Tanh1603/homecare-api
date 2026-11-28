package com.homecare.household.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "warranties")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warranty {

    @Id
    private UUID id;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "owner_profile_id", nullable = false, length = 255)
    private String ownerProfileId;

    @Column(name = "owner_name", length = 255)
    private String ownerName;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "MANUAL";

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "unit_key", length = 128, unique = true)
    private String unitKey;

    @Column(name = "start_trigger", nullable = false, length = 40)
    @Builder.Default
    private String startTrigger = "DELIVERY_DATE";

    @Column(name = "warranty_responsible_org_id")
    private UUID warrantyResponsibleOrgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "manufacturer_registration_status", nullable = false, length = 40)
    @Builder.Default
    private ManufacturerRegistrationStatus manufacturerRegistrationStatus = ManufacturerRegistrationStatus.NOT_VERIFIED;

    @Column(name = "trigger_evidence_ref", length = 1000)
    private String triggerEvidenceRef;

    @Column(name = "coverage_months")
    private Integer coverageMonths;

    @Column(length = 1000)
    private String notes;

    @Column(name = "document_url")
    private String documentUrl;

    @Column(nullable = false)
    private String status;

    @Column(name = "warranty_category", nullable = false, length = 32)
    @Builder.Default
    private String warrantyCategory = "PRODUCT_MANUFACTURER";

    @Column(name = "warranty_issuer", nullable = false, length = 24)
    @Builder.Default
    private String warrantyIssuer = "MANUFACTURER";

    @Column(name = "verification_status", nullable = false, length = 24)
    @Builder.Default
    private String verificationStatus = "UNVERIFIED";

    @Column(name = "validity_status", nullable = false, length = 24)
    @Builder.Default
    private String validityStatus = "ACTIVE";

    @Column(name = "authorization_evidence_ref", length = 1000)
    private String authorizationEvidenceRef;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_note", length = 1000)
    private String verificationNote;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
