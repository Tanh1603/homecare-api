package com.homecare.household.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.CareRecord;

public interface CareRecordRepository extends JpaRepository<CareRecord, UUID> {
    List<CareRecord> findByDeviceIdOrderByOccurredAtDesc(UUID deviceId);

    Optional<CareRecord> findByBookingId(UUID bookingId);
}
