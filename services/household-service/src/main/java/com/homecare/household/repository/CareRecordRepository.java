package com.homecare.household.repository;

import com.homecare.household.entity.CareRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CareRecordRepository extends JpaRepository<CareRecord, UUID> {
    void deleteByDeviceId(UUID deviceId);
    List<CareRecord> findByDeviceIdOrderByOccurredAtDesc(UUID deviceId);

    Optional<CareRecord> findByBookingId(UUID bookingId);
}
