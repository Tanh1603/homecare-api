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
@Table(name = "organization_enforcements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationEnforcementEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "action_type", nullable = false, length = 30)
    private String actionType;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "evidence_refs", columnDefinition = "text")
    private String evidenceRefs;

    @Column(name = "safety_intervention_flag", nullable = false)
    @Builder.Default
    private boolean safetyInterventionFlag = false;

    @Column(name = "issued_by_admin", nullable = false, length = 255)
    private String issuedByAdmin;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
}
