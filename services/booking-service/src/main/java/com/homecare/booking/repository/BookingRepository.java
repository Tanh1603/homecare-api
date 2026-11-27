package com.homecare.booking.repository;

import com.homecare.booking.entity.BookingEntity;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID>, JpaSpecificationExecutor<BookingEntity> {
    boolean existsByDeviceId(UUID deviceId);
    Optional<BookingEntity> findByOrderLineIdAndPurpose(UUID orderLineId, String purpose);

    List<BookingEntity> findByProviderIdAndStatusIn(UUID providerId, List<String> statuses);

    @Query("""
        select b from BookingEntity b
        where b.status in :statuses
          and (b.requesterProfileId = :userId or b.assignedTechnicianProfileId = :userId)
        """)
    List<BookingEntity> findOpenBookingsForUser(@Param("userId") String userId, @Param("statuses") List<String> statuses);
    @Lock(PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :id")
    Optional<BookingEntity> lockById(UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update BookingEntity b
        set b.allocationStatus = 'ALLOCATING', b.updatedAt = :now
        where b.id = :bookingId
          and b.status = 'REQUESTED'
          and b.allocationStatus = 'UNALLOCATED'
        """)
    int tryClaimForAllocation(@Param("bookingId") UUID bookingId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update BookingEntity b
        set b.allocationStatus = 'REASSIGNING', b.updatedAt = :now
        where b.id = :bookingId
          and b.status = 'ASSIGNED'
          and b.allocationStatus = 'ALLOCATED'
        """)
    int tryClaimForReassignment(@Param("bookingId") UUID bookingId, @Param("now") Instant now);

    @Query(value = """
        SELECT * FROM bookings
        WHERE status = 'COMPLETED_PENDING_ACCEPTANCE'
          AND completion_notified_at IS NOT NULL
          AND notification_failed = FALSE
          AND auto_accept_at IS NOT NULL
          AND auto_accept_at <= :now
        ORDER BY auto_accept_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<BookingEntity> lockDueForAutoAccept(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM bookings
        WHERE status = 'COMPLETED_PENDING_ACCEPTANCE'
          AND completion_notified_at IS NOT NULL
          AND notification_failed = FALSE
          AND acceptance_reminder_sent_at IS NULL
          AND completion_notified_at <= :day3Cutoff
        ORDER BY completion_notified_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<BookingEntity> lockDueForAcceptanceReminder(@Param("day3Cutoff") Instant day3Cutoff, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM bookings
        WHERE status = 'REVISION_REQUESTED'
          AND revision_requested_at IS NOT NULL
          AND revision_requested_at <= :cutoff48h
        ORDER BY revision_requested_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<BookingEntity> lockTimedOutRevisions(@Param("cutoff48h") Instant cutoff48h, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM bookings
        WHERE status = 'WAITING_FOR_PARTS'
          AND waiting_for_parts_since IS NOT NULL
          AND waiting_for_parts_reminder_stage < :targetStage
          AND waiting_for_parts_since <= :cutoff
        ORDER BY waiting_for_parts_since ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<BookingEntity> lockWaitingForPartsDue(
            @Param("targetStage") int targetStage,
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit);
}
