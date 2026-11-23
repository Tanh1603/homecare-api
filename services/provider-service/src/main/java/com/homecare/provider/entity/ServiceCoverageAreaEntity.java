package com.homecare.provider.entity;

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
@Table(name = "service_coverage_areas")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCoverageAreaEntity {

    @Id
    private UUID id;

    @Column(name = "service_offering_id", nullable = false)
    private UUID serviceOfferingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "level", nullable = false, length = 20)
    private String level;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
