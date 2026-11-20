package com.homecare.commerce.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String brand;

    @Column(name = "product_type", nullable = false)
    @Builder.Default
    private String productType = "SMALL_PART";

    @Column(name = "model", length = 255)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_eligibility", nullable = false, length = 32)
    @Builder.Default
    private DeviceEligibility deviceEligibility = DeviceEligibility.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "warranty_start_trigger", nullable = false, length = 40)
    @Builder.Default
    private WarrantyStartTrigger warrantyStartTrigger = WarrantyStartTrigger.DELIVERY_DATE;

    @Column(name = "installable", nullable = false)
    @Builder.Default
    private boolean installable = false;

    @Column(name = "default_install_category", length = 255)
    private String defaultInstallCategory;

    @Column(name = "warranty_months", nullable = false)
    @Builder.Default
    private int warrantyMonths = 0;

    @Column(name = "return_window_days", nullable = false)
    @Builder.Default
    private int returnWindowDays = 7;

    @Column(length = 1500)
    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private String status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
