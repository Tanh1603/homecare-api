package com.homecare.booking.entity;

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
@Table(name = "disputes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "opened_by_profile_id", nullable = false, length = 255)
    private String openedByProfileId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "opened_stage", nullable = false, length = 32)
    private String openedStage;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "provider_response", length = 2000)
    private String providerResponse;

    @Column(name = "provider_responded_at")
    private Instant providerRespondedAt;

    @Column(name = "provider_no_response", nullable = false)
    @Builder.Default
    private boolean providerNoResponse = false;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "sla_reminder_stage", nullable = false)
    @Builder.Default
    private int slaReminderStage = 0;

    @Column(name = "resolution_proposal", length = 2000)
    private String resolutionProposal;

    @Column(name = "resolution_outcome", length = 40)
    private String resolutionOutcome;

    @Column(name = "resolved_by_admin_id", length = 255)
    private String resolvedByAdminId;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
