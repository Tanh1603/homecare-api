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
@Table(name = "service_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRequestEntity {

    @Id
    private UUID id;

    @Column(name = "requester_profile_id", nullable = false, length = 255)
    private String requesterProfileId;

    @Column(name = "requester_name", length = 255)
    private String requesterName;

    @Column(name = "request_type", nullable = false, length = 40)
    private String requestType;

    @Column(name = "service_category", nullable = false, length = 100)
    private String serviceCategory;

    @Column(name = "service_offering_id")
    private UUID serviceOfferingId;

    @Column(name = "price_type", nullable = false, length = 30)
    @Builder.Default
    private String priceType = "FIXED";

    @Column(name = "requires_onsite_survey", nullable = false)
    @Builder.Default
    private boolean requiresOnsiteSurvey = false;

    @Column(name = "inspection_fee", nullable = false)
    @Builder.Default
    private long inspectionFee = 0L;

    @Column(name = "policy_version", nullable = false, length = 64)
    @Builder.Default
    private String policyVersion = "v1";

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "warranty_id")
    private UUID warrantyId;

    @Column(name = "warranty_status", length = 40)
    private String warrantyStatus;

    @Column(name = "warranty_responsible_org_id")
    private UUID warrantyResponsibleOrgId;

    @Column(name = "assigned_provider_id")
    private UUID assignedProviderId;

    @Column(name = "fallback_reason", length = 120)
    private String fallbackReason;

    @Column(name = "fallback_notified_at")
    private Instant fallbackNotifiedAt;

    @Column(name = "diagnostic_booking_id")
    private UUID diagnosticBookingId;

    @Column(name = "repair_booking_id")
    private UUID repairBookingId;

    @Column(name = "accepted_quotation_id")
    private UUID acceptedQuotationId;

    @Column(name = "issue_description", length = 2000)
    private String issueDescription;

    @Column(name = "preferred_start", nullable = false)
    private Instant preferredStart;

    @Column(name = "preferred_end", nullable = false)
    private Instant preferredEnd;

    @Column(nullable = false, length = 1000)
    private String address;

    @Column(name = "service_address_id")
    private UUID serviceAddressId;

    @Column(name = "recipient_name", length = 255)
    private String recipientName;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "address_line", length = 500)
    private String addressLine;

    @Column(name = "ward_code", length = 32)
    private String wardCode;

    @Column(name = "ward_name", length = 255)
    private String wardName;

    @Column(name = "province_code", length = 32)
    private String provinceCode;

    @Column(name = "province_name", length = 255)
    private String provinceName;

    @Column(name = "formatted_address", length = 1000)
    private String formattedAddress;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(nullable = false, length = 50)
    private String status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
