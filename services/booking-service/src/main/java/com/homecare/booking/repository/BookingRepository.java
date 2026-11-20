package com.homecare.booking.repository;

import com.homecare.booking.entity.BookingEntity;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID>, JpaSpecificationExecutor<BookingEntity> {
    Optional<BookingEntity> findByOrderLineIdAndPurpose(UUID orderLineId, String purpose);
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
}
