package com.homecare.booking.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.WarrantyActivationSnapshotEntity;

public interface WarrantyActivationSnapshotRepository extends JpaRepository<WarrantyActivationSnapshotEntity, UUID> {

    Optional<WarrantyActivationSnapshotEntity> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);
}
