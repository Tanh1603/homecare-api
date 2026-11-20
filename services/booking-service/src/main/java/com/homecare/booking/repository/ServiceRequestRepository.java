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
}
