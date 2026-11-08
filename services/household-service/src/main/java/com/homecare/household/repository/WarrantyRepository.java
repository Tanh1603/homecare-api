package com.homecare.household.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.Warranty;

public interface WarrantyRepository extends JpaRepository<Warranty, UUID> {
    List<Warranty> findByDeviceIdOrderByEndsOnDesc(UUID deviceId);

    Optional<Warranty> findByOrderLineId(UUID orderLineId);
}
