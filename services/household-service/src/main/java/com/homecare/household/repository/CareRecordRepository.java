package com.homecare.household.repository;

import com.homecare.household.entity.CareRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface CareRecordRepository extends JpaRepository<CareRecord, UUID> {
    void deleteByDeviceId(UUID deviceId);
    List<CareRecord> findByDeviceIdOrderByOccurredAtDesc(UUID deviceId);

    @Lock(PESSIMISTIC_WRITE)
    Optional<CareRecord> findByBookingId(UUID bookingId);
    @Lock(PESSIMISTIC_WRITE)
    Optional<CareRecord> findFirstByDeviceIdAndKindOrderByCreatedAtAsc(UUID deviceId, String kind);
    boolean existsByDeviceIdAndKind(UUID deviceId, String kind);
}
