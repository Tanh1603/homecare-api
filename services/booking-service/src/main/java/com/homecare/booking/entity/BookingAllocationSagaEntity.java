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
@Table(name = "booking_allocation_saga")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingAllocationSagaEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "intent_id", nullable = false, unique = true)
    private String intentId;

    @Column(name = "saga_status", nullable = false)
    private String sagaStatus;

    @Column(name = "current_step", nullable = false)
    private String currentStep;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "compensation_reason", length = 500)
    private String compensationReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Version
    private Long version;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
