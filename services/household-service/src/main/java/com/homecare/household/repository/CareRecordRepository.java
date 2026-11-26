package com.homecare.household.repository;

import com.homecare.household.entity.CareRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

public interface CareRecordRepository extends JpaRepository<CareRecord, UUID> {
    void deleteByDeviceId(UUID deviceId);
    boolean existsByDeviceId(UUID deviceId);
    List<CareRecord> findByDeviceIdOrderByOccurredAtDesc(UUID deviceId);
    Page<CareRecord> findByDeviceIdOrderByOccurredAtDesc(UUID deviceId, Pageable pageable);

    @Lock(PESSIMISTIC_WRITE)
    Optional<CareRecord> findByBookingId(UUID bookingId);
    @Lock(PESSIMISTIC_WRITE)
    Optional<CareRecord> findFirstByDeviceIdAndKindOrderByCreatedAtAsc(UUID deviceId, String kind);
    boolean existsByDeviceIdAndKind(UUID deviceId, String kind);
}
