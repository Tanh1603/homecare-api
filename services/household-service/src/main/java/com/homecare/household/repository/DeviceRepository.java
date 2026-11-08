package com.homecare.household.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.Device;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
    List<Device> findByOwnerProfileIdOrderByCreatedAtDesc(String ownerProfileId);
}
