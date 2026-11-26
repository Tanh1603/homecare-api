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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceSchedule, UUID> {
    void deleteByDeviceId(UUID deviceId);
    Page<MaintenanceSchedule> findByOwnerProfileId(String ownerProfileId, Pageable pageable);
    List<MaintenanceSchedule> findByDeviceIdOrderByDueOnAsc(UUID deviceId);
    Page<MaintenanceSchedule> findByDeviceIdOrderByDueOnAsc(UUID deviceId, Pageable pageable);
    boolean existsByDeviceIdAndPolicyVersion(UUID deviceId, Integer policyVersion);

    @Lock(PESSIMISTIC_WRITE)
    List<MaintenanceSchedule> findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual(String status, LocalDate date);

    @Query(value = """
            SELECT s.*
            FROM maintenance_schedules s
            WHERE s.status = 'SCHEDULED'
              AND s.reminder_enabled = TRUE
              AND s.notified_at IS NULL
              AND s.due_on <= :referenceDate
              AND EXISTS (
                  SELECT 1 FROM devices d
                  WHERE d.id = s.device_id AND d.status = 'ACTIVE'
              )
            ORDER BY s.due_on ASC
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MaintenanceSchedule> findDueActiveSchedulesForUpdateSkipLocked(@Param("referenceDate") LocalDate referenceDate);
}
