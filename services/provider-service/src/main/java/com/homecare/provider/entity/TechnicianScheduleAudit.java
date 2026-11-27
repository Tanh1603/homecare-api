package com.homecare.provider.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "technician_schedule_audits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicianScheduleAudit {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 32)
    private String scope;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_until")
    private LocalDate effectiveUntil;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
