package com.homecare.provider.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "provider_category_qualifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryQualificationEntity {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ServiceCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CategoryQualificationStatus status = CategoryQualificationStatus.UNVERIFIED;

    @Column(name = "evidence_refs", nullable = false, columnDefinition = "text")
    @Builder.Default
    private String evidenceRefs = "[]";

    @Column(length = 1000)
    private String reason;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
