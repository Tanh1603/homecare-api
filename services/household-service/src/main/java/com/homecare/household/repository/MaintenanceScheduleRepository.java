package com.homecare.household.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.MaintenanceSchedule;

public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceSchedule, UUID> {
    List<MaintenanceSchedule> findByDeviceIdOrderByDueOnAsc(UUID deviceId);
    List<MaintenanceSchedule> findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual(String status, LocalDate date);
}
