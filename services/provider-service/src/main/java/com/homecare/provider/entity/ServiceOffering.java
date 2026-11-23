package com.homecare.provider.entity;

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
@Table(name = "service_offerings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOffering {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(length = 1000)
    private String description;

    @Column(name = "base_price", nullable = false)
    private long basePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 30)
    @Builder.Default
    private ServicePriceType priceType = ServicePriceType.FIXED;

    @Column(name = "inspection_fee")
    private Long inspectionFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "inspection_fee_policy", nullable = false, length = 30)
    @Builder.Default
    private InspectionFeePolicy inspectionFeePolicy = InspectionFeePolicy.NO_FEE;

    @Column(name = "inspection_fee_audit_flag", nullable = false)
    @Builder.Default
    private boolean inspectionFeeAuditFlag = false;

    @Column(name = "workmanship_duration_days", nullable = false)
    @Builder.Default
    private int workmanshipDurationDays = 7;

    @Column(name = "price_version", nullable = false)
    @Builder.Default
    private int priceVersion = 1;

    @Column(name = "estimated_duration_minutes", nullable = false)
    private int estimatedDurationMinutes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
