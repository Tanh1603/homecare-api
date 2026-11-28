package com.homecare.booking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.WarrantyCommitmentSnapshotEntity;

public interface WarrantyCommitmentSnapshotRepository extends JpaRepository<WarrantyCommitmentSnapshotEntity, UUID> {

    List<WarrantyCommitmentSnapshotEntity> findByBookingIdOrderByQuotationVersionAsc(UUID bookingId);

    Optional<WarrantyCommitmentSnapshotEntity> findTopByBookingIdOrderByQuotationVersionDesc(UUID bookingId);

    boolean existsByBookingIdAndQuotationVersion(UUID bookingId, int quotationVersion);
}
