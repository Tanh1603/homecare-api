package com.homecare.commerce.entity;

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
@Table(name = "order_component_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderComponentSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_line_id", nullable = false)
    private UUID orderLineId;

    @Column(name = "component_type", nullable = false, length = 32)
    private String componentType;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "service_offering_id")
    private UUID serviceOfferingId;

    @Column(name = "allocated_gross_amount", nullable = false)
    private long allocatedGrossAmount;

    @Column(name = "allocated_discount_amount", nullable = false)
    private long allocatedDiscountAmount;

    @Column(name = "refundable_remaining_amount", nullable = false)
    private long refundableRemainingAmount;

    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
