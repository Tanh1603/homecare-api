package com.homecare.household.entity;

import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "warranties")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warranty {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "owner_profile_id", nullable = false, length = 255)
    private String ownerProfileId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "MANUAL";

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(length = 1000)
    private String notes;

    @Column(name = "document_url")
    private String documentUrl;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
