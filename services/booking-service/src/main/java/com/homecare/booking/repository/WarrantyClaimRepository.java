package com.homecare.booking.repository;

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

import com.homecare.booking.entity.WarrantyClaimEntity;

import jakarta.persistence.LockModeType;

public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaimEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WarrantyClaimEntity c WHERE c.id = :id")
    Optional<WarrantyClaimEntity> lockById(@Param("id") UUID id);

    @Query("""
            SELECT c FROM WarrantyClaimEntity c
            WHERE c.bookingId = :bookingId
              AND c.warrantyCategory = 'WORKMANSHIP'
              AND c.claimStatus IN ('SUBMITTED', 'PENDING_VERIFICATION')
            """)
    Optional<WarrantyClaimEntity> findOpenWorkmanshipByBookingId(@Param("bookingId") UUID bookingId);

    Page<WarrantyClaimEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId, Pageable pageable);

    Page<WarrantyClaimEntity> findByProviderIdOrderByCreatedAtDesc(UUID providerId, Pageable pageable);

    Page<WarrantyClaimEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            SELECT c FROM WarrantyClaimEntity c
            WHERE (:requesterProfileId IS NULL OR c.requesterProfileId = :requesterProfileId)
              AND (:providerId IS NULL OR c.providerId = :providerId)
              AND (:claimStatus IS NULL OR c.claimStatus = :claimStatus)
              AND (:warrantyCategory IS NULL OR c.warrantyCategory = :warrantyCategory)
              AND (:providerNoResponse IS NULL OR c.providerNoResponse = :providerNoResponse)
            ORDER BY c.createdAt DESC
            """)
    Page<WarrantyClaimEntity> findFiltered(
            @Param("requesterProfileId") String requesterProfileId,
            @Param("providerId") UUID providerId,
            @Param("claimStatus") String claimStatus,
            @Param("warrantyCategory") String warrantyCategory,
            @Param("providerNoResponse") Boolean providerNoResponse,
            Pageable pageable);

    @Query(value = """
            SELECT * FROM warranty_claims
            WHERE warranty_category = 'WORKMANSHIP'
              AND claim_status = 'SUBMITTED'
              AND provider_no_response = FALSE
              AND provider_response_deadline_at IS NOT NULL
              AND provider_response_deadline_at <= :now
            ORDER BY provider_response_deadline_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WarrantyClaimEntity> lockOverdueWorkmanshipClaims(@Param("now") Instant now, @Param("limit") int limit);
}
