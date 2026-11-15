package com.homecare.household.repository;

import com.homecare.household.entity.MaintenanceSchedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceSchedule, UUID> {
    void deleteByDeviceId(UUID deviceId);
    Page<MaintenanceSchedule> findByOwnerProfileId(String ownerProfileId, Pageable pageable);
    List<MaintenanceSchedule> findByDeviceIdOrderByDueOnAsc(UUID deviceId);
    List<MaintenanceSchedule> findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual(String status, LocalDate date);
}
