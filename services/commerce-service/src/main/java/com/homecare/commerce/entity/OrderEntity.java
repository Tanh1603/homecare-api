package com.homecare.commerce.entity;

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
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "buyer_profile_id", nullable = false, length = 255)
    private String buyerProfileId;

    @Column(name = "buyer_name", length = 255)
    private String buyerName;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    @Column(name = "shipping_address_id")
    private UUID shippingAddressId;

    @Column(name = "shipping_recipient_name", length = 255)
    private String shippingRecipientName;

    @Column(name = "shipping_phone", length = 50)
    private String shippingPhone;

    @Column(name = "shipping_address_line", length = 500)
    private String shippingAddressLine;

    @Column(name = "shipping_ward_code", length = 32)
    private String shippingWardCode;

    @Column(name = "shipping_ward_name", length = 255)
    private String shippingWardName;

    @Column(name = "shipping_province_code", length = 32)
    private String shippingProvinceCode;

    @Column(name = "shipping_province_name", length = 255)
    private String shippingProvinceName;

    @Column(name = "shipping_formatted_address", length = 1000)
    private String shippingFormattedAddress;

    @Column(name = "shipping_latitude")
    private Double shippingLatitude;

    @Column(name = "shipping_longitude")
    private Double shippingLongitude;

    @Column(name = "install_offering_id")
    private UUID installOfferingId;

    @Column(name = "service_address_id")
    private UUID serviceAddressId;

    @Column(name = "preferred_install_start")
    private Instant preferredInstallStart;

    @Column(name = "install_amount", nullable = false)
    @Builder.Default
    private long installAmount = 0L;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private String status;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus;

    @Column(name = "quote_expires_at", nullable = false)
    private Instant quoteExpiresAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
