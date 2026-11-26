package com.homecare.booking.repository;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.booking.entity.DisputeEntity;

public interface DisputeRepository extends JpaRepository<DisputeEntity, UUID> {

    @Lock(PESSIMISTIC_WRITE)
    @Query("select d from DisputeEntity d where d.id = :id")
    Optional<DisputeEntity> lockById(@Param("id") UUID id);

    @Query("""
        select d from DisputeEntity d
        where d.bookingId = :bookingId
          and d.status not in ('RESOLVED', 'ESCALATED', 'CLOSED')
        """)
    Optional<DisputeEntity> findActiveByBookingId(@Param("bookingId") UUID bookingId);

    List<DisputeEntity> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    Page<DisputeEntity> findByOpenedByProfileId(String profileId, Pageable pageable);

    Page<DisputeEntity> findByProviderId(UUID providerId, Pageable pageable);

    Page<DisputeEntity> findByStatus(String status, Pageable pageable);

    @Query(value = """
        SELECT * FROM disputes
        WHERE status = 'AWAITING_PROVIDER_RESPONSE'
          AND sla_due_at IS NOT NULL
          AND sla_due_at <= :now
        ORDER BY sla_due_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<DisputeEntity> lockExpiredProviderSla(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM disputes
        WHERE status = 'AWAITING_PROVIDER_RESPONSE'
          AND sla_reminder_stage = 0
          AND sla_due_at IS NOT NULL
          AND sla_due_at <= :reminderCutoff
        ORDER BY sla_due_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<DisputeEntity> lockProviderSlaReminderDue(@Param("reminderCutoff") Instant reminderCutoff, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM disputes
        WHERE status IN ('AWAITING_CUSTOMER_EVIDENCE', 'RESOLUTION_PROPOSED')
          AND sla_reminder_stage < :targetStage
          AND sla_due_at IS NOT NULL
          AND sla_due_at <= :reminderCutoff
        ORDER BY sla_due_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<DisputeEntity> lockCustomerSlaReminderDue(
            @Param("reminderCutoff") Instant reminderCutoff,
            @Param("targetStage") int targetStage,
            @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM disputes
        WHERE status IN ('AWAITING_CUSTOMER_EVIDENCE', 'RESOLUTION_PROPOSED')
          AND sla_due_at IS NOT NULL
          AND sla_due_at <= :now
        ORDER BY sla_due_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<DisputeEntity> lockExpiredCustomerSla(@Param("now") Instant now, @Param("limit") int limit);
}
