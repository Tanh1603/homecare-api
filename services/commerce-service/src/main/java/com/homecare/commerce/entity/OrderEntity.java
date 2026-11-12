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

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

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
