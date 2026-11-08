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
@Table(name = "booking_work_updates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkUpdateEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "technician_profile_id", nullable = false, length = 255)
    private String technicianProfileId;

    @Column(nullable = false)
    private String kind;

    @Column(length = 1500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
