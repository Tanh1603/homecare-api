package com.homecare.household.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import com.homecare.household.entity.Device;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
    Page<Device> findByHouseholdIdOrderByCreatedAtDesc(UUID householdId, Pageable pageable);
    Page<Device> findByOwnerProfileIdOrderByCreatedAtDesc(String ownerProfileId, Pageable pageable);
    List<Device> findByOwnerProfileIdOrderByCreatedAtDesc(String ownerProfileId);
    Optional<Device> findByUnitKey(String unitKey);

    @Lock(PESSIMISTIC_WRITE)
    Optional<Device> findLockedById(UUID id);
}
