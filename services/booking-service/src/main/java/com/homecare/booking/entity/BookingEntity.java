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

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private String status;

    @Column(name = "allocation_status", nullable = false)
    private String allocationStatus;

    @Column(name = "quoted_price", nullable = false)
    private long quotedPrice;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
