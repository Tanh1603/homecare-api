package com.homecare.commerce.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "part_compatibilities")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartCompatibilityEntity {

    public enum CompatibilityStatus {
        VERIFIED_COMPATIBLE,
        UNKNOWN,
        INCOMPATIBLE
    }

    @Id
    private UUID id;

    @Column(name = "part_product_id", nullable = false)
    private UUID partProductId;

    @Column(name = "device_type", nullable = false, length = 100)
    private String deviceType;

    @Column(name = "device_brand", nullable = false, length = 100)
    private String deviceBrand;

    @Column(name = "device_model", nullable = false, length = 255)
    private String deviceModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "compatibility_status", nullable = false, length = 32)
    private CompatibilityStatus compatibilityStatus;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
