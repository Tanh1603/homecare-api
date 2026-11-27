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
@Table(name = "bookings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEntity {

    @Id
    private UUID id;

    @Column(name = "requester_profile_id", nullable = false, length = 255)
    private String requesterProfileId;

    @Column(name = "requester_name", length = 255)
    private String requesterName;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "preferred_provider_id")
    private UUID preferredProviderId;

    @Column(name = "booking_type", nullable = false)
    @Builder.Default
    private String bookingType = "STANDARD";

    @Column(name = "service_request_id")
    private UUID serviceRequestId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "product_ready", nullable = false)
    @Builder.Default
    private boolean productReady = false;

    @Column(name = "purpose", nullable = false, length = 32)
    @Builder.Default
    private String purpose = "REPAIR";

    @Column(name = "warranty_id")
    private UUID warrantyId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    @Column(name = "assigned_technician_profile_id", length = 255)
    private String assignedTechnicianProfileId;

    @Column(name = "assigned_technician_name")
    private String assignedTechnicianName;

    @Column(name = "preferred_start", nullable = false)
    private Instant preferredStart;

    @Column(name = "preferred_end", nullable = false)
    private Instant preferredEnd;

    @Column(nullable = false)
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

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private String status;

    @Column(name = "allocation_status", nullable = false)
    private String allocationStatus;

    @Column(name = "active_allocation_operation_id")
    private UUID activeAllocationOperationId;

    @Column(name = "quoted_price", nullable = false)
    private long quotedPrice;

    @Column(name = "admin_review_flagged", nullable = false)
    @Builder.Default
    private boolean adminReviewFlagged = false;

    @Column(name = "admin_review_reason", length = 1000)
    private String adminReviewReason;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "inspection_fee_policy", nullable = false, length = 30)
    @Builder.Default
    private String inspectionFeePolicy = InspectionFeePolicy.NO_FEE.name();

    @Column(name = "reschedule_count", nullable = false)
    @Builder.Default
    private int rescheduleCount = 0;

    @Column(name = "pending_reschedule_start")
    private Instant pendingRescheduleStart;

    @Column(name = "pending_reschedule_end")
    private Instant pendingRescheduleEnd;

    @Column(name = "pending_reschedule_by", length = 40)
    private String pendingRescheduleBy;

    @Column(name = "pending_reschedule_reason", length = 500)
    private String pendingRescheduleReason;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "waiting_for_parts_since")
    private Instant waitingForPartsSince;

    @Column(name = "waiting_for_parts_reminder_stage", nullable = false)
    @Builder.Default
    private int waitingForPartsReminderStage = 0;

    @Column(name = "completion_submitted_at")
    private Instant completionSubmittedAt;

    @Column(name = "completion_summary", length = 2000)
    private String completionSummary;

    @Column(name = "completion_notified_at")
    private Instant completionNotifiedAt;

    @Column(name = "notification_failed", nullable = false)
    @Builder.Default
    private boolean notificationFailed = false;

    @Column(name = "auto_accept_at")
    private Instant autoAcceptAt;

    @Column(name = "acceptance_reminder_sent_at")
    private Instant acceptanceReminderSentAt;

    @Column(name = "acceptance_source", length = 32)
    private String acceptanceSource;

    @Column(name = "revision_count", nullable = false)
    @Builder.Default
    private int revisionCount = 0;

    @Column(name = "revision_requested_at")
    private Instant revisionRequestedAt;

    @Column(name = "customer_no_show_evidence_id")
    private UUID customerNoShowEvidenceId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
