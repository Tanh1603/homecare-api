package com.homecare.provider.repository;

import com.homecare.provider.entity.TechnicianAllocation;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianAllocationRepository extends JpaRepository<TechnicianAllocation, UUID> {
    Optional<TechnicianAllocation> findByIntentId(String intentId);
    @Lock(PESSIMISTIC_WRITE)
    @Query("select a from TechnicianAllocation a where a.operationId = :operationId")
    Optional<TechnicianAllocation> lockByOperationId(UUID operationId);

    @Lock(PESSIMISTIC_WRITE)
    @Query("select a from TechnicianAllocation a where a.bookingId = :bookingId and a.status = 'ALLOCATED'")
    Optional<TechnicianAllocation> lockByBookingId(UUID bookingId);

    Optional<TechnicianAllocation> findByBookingIdAndStatus(UUID bookingId, String status);

    @Query("""
            select a from TechnicianAllocation a
            where a.technicianId = :techId
              and a.status = 'ALLOCATED'
              and a.startsAt < :reqEnd
              and a.endsAt > :reqStart
            """)
    List<TechnicianAllocation> findActiveOverlapping(
            @Param("techId") UUID techId,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);
}
