package com.homecare.provider.organization.entity;

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
@Table(name = "organizations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrganizationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private OrganizationStatus status = OrganizationStatus.PENDING;

    @Column(name = "reject_reason", length = 1000)
    private String rejectReason;

    @Column(name = "tax_code", length = 100)
    private String taxCode;

    @Column(nullable = false, length = 50)
    private String phone;

    @Column(nullable = false, length = 150)
    private String city;

    @Column(length = 500)
    private String address;

    @Column(length = 1000)
    private String description;

    @Column(name = "owner_user_id", nullable = false, length = 255)
    private String ownerUserId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
