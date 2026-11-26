package com.homecare.booking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.booking.entity.ServiceRequestEntity;

import jakarta.persistence.LockModeType;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequestEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ServiceRequestEntity r WHERE r.id = :id")
    Optional<ServiceRequestEntity> lockById(@Param("id") UUID id);

    Page<ServiceRequestEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId, Pageable pageable);

    Page<ServiceRequestEntity> findByAssignedProviderIdOrderByCreatedAtDesc(UUID assignedProviderId, Pageable pageable);

    List<ServiceRequestEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId);

    List<ServiceRequestEntity> findByServiceOfferingIdAndStatusIn(UUID serviceOfferingId, List<String> statuses);

    List<ServiceRequestEntity> findByAssignedProviderIdAndStatusIn(UUID assignedProviderId, List<String> statuses);

    @Query(value = """
            SELECT * FROM service_requests
            WHERE status = 'AWAITING_PROVIDER'
              AND status_changed_at <= :cutoff
            ORDER BY status_changed_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ServiceRequestEntity> lockTimedOutAwaitingProvider(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM service_requests
            WHERE status IN ('AWAITING_ALTERNATIVE', 'QUOTATION_REJECTED')
              AND expiry_warning_notified_at IS NULL
              AND status_changed_at <= :cutoff
            ORDER BY status_changed_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ServiceRequestEntity> lockDueForExpiryWarning(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM service_requests
            WHERE status IN ('AWAITING_ALTERNATIVE', 'QUOTATION_REJECTED')
              AND status_changed_at <= :cutoff
            ORDER BY status_changed_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ServiceRequestEntity> lockIdleExpired(@Param("cutoff") java.time.Instant cutoff, @Param("limit") int limit);
}
