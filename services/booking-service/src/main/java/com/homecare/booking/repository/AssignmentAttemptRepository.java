package com.homecare.booking.repository;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import com.homecare.booking.entity.AssignmentAttemptEntity;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AssignmentAttemptRepository extends JpaRepository<AssignmentAttemptEntity, UUID> {

    @Lock(PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        select a from AssignmentAttemptEntity a
        where a.status in ('ALLOCATING', 'RECONCILIATION_PENDING', 'COMPENSATION_PENDING')
          and (a.leaseUntil is null or a.leaseUntil < :now)
          and (a.nextAttemptAt is null or a.nextAttemptAt <= :now)
        order by a.updatedAt asc
        """)
    List<AssignmentAttemptEntity> findDueForRecovery(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AssignmentAttemptEntity a
        set a.status = :newStatus,
            a.leaseUntil = :leaseUntil,
            a.lastError = :lastError,
            a.updatedAt = :now
        where a.operationId = :operationId
          and a.claimToken = :claimToken
          and a.status = :expectedStatus
        """)
    int transitionKeepingLease(
            @Param("operationId") UUID operationId,
            @Param("claimToken") UUID claimToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("lastError") String lastError,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AssignmentAttemptEntity a
        set a.status = :newStatus,
            a.leaseUntil = null,
            a.lastError = :lastError,
            a.updatedAt = :now
        where a.operationId = :operationId
          and a.claimToken = :claimToken
          and a.status = :expectedStatus
        """)
    int transitionWithFence(
            @Param("operationId") UUID operationId,
            @Param("claimToken") UUID claimToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("lastError") String lastError,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update AssignmentAttemptEntity a
        set a.status = :newStatus,
            a.leaseUntil = null,
            a.retryCount = a.retryCount + 1,
            a.nextAttemptAt = :nextAttemptAt,
            a.lastError = :lastError,
            a.updatedAt = :now
        where a.operationId = :operationId
          and a.claimToken = :claimToken
          and a.status = :expectedStatus
        """)
    int scheduleRetryWithFence(
            @Param("operationId") UUID operationId,
            @Param("claimToken") UUID claimToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") Instant now);

    List<AssignmentAttemptEntity> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);
}
