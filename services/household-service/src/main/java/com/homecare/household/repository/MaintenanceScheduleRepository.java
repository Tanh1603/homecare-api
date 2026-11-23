package com.homecare.household.repository;

import com.homecare.household.entity.MaintenanceSchedule;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceSchedule, UUID> {
    void deleteByDeviceId(UUID deviceId);
    Page<MaintenanceSchedule> findByOwnerProfileId(String ownerProfileId, Pageable pageable);
    List<MaintenanceSchedule> findByDeviceIdOrderByDueOnAsc(UUID deviceId);
    boolean existsByDeviceIdAndPolicyVersion(UUID deviceId, Integer policyVersion);

    @Lock(PESSIMISTIC_WRITE)
    List<MaintenanceSchedule> findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual(String status, LocalDate date);
}
