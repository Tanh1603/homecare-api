package com.homecare.commerce.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "order_return_requests",
        indexes = @Index(
                name = "idx_order_return_org_status_created",
                columnList = "organization_id, status, created_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReturnRequestEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_line_id", nullable = false)
    private UUID orderLineId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "buyer_profile_id", nullable = false)
    private String buyerProfileId;

    @Column(name = "buyer_name", length = 255)
    private String buyerName;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "return_type", nullable = false)
    private String returnType;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean restocked = false;

    @Version
    @Builder.Default
    @Column(columnDefinition = "bigint default 0")
    private Long version = 0L;
}
