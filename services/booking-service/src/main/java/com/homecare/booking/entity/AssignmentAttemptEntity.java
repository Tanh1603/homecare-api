package com.homecare.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "booking_assignment_attempts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentAttemptEntity {

    @Id
    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "preferred_start", nullable = false)
    private Instant preferredStart;

    @Column(name = "preferred_end", nullable = false)
    private Instant preferredEnd;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "strict_provider_match", nullable = false)
    private boolean strictProviderMatch;

    @Column(name = "technician_id")
    private UUID technicianId;

    @Column(nullable = false)
    private String status;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
