package com.homecare.provider.entity;

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
@Table(name = "technicians")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Technician {

    @Id
    private UUID id;

    @Column(name = "profile_id", nullable = false, unique = true, length = 255)
    private String profileId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String specialties;

    @Column(name = "experience_years", nullable = false)
    private int experienceYears;

    @Column(name = "rating_avg", nullable = false)
    private double ratingAvg;

    @Column(name = "active_allocations", nullable = false)
    private int activeAllocations;

    @Column(nullable = false)
    private String status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
