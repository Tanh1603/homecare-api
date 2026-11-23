package com.homecare.provider.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "organization_appeals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationAppealEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "enforcement_id", nullable = false)
    private UUID enforcementId;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private int attemptNumber = 1;

    @Column(name = "appeal_reason", nullable = false, length = 2000)
    private String appealReason;

    @Column(name = "evidence_refs", columnDefinition = "text")
    private String evidenceRefs;

    @Column(name = "submitted_by", nullable = false, length = 255)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "sla_due_at", nullable = false)
    private Instant slaDueAt;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "SUBMITTED";

    @Column(name = "reviewed_by_admin", length = 255)
    private String reviewedByAdmin;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;
}
