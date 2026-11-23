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
@Table(name = "user_enforcement_audits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEnforcementAuditEntity {

    @Id
    private UUID id;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "new_status", nullable = false, length = 30)
    private String newStatus;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "issued_by_admin", nullable = false, length = 255)
    private String issuedByAdmin;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
}
