package com.homecare.household.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.Household;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {
    List<Household> findByOwnerProfileIdOrderByCreatedAtDesc(String ownerProfileId);
    Page<Household> findByOwnerProfileIdOrderByCreatedAtDesc(String ownerProfileId, Pageable pageable);
    List<Household> findByOwnerProfileIdAndDefaultServiceAddressId(String ownerProfileId, UUID defaultServiceAddressId);
}
