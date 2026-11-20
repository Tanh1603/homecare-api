package com.homecare.household.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.household.entity.MaintenancePolicyEntity;

public interface MaintenancePolicyRepository extends JpaRepository<MaintenancePolicyEntity, UUID> {
    Optional<MaintenancePolicyEntity> findFirstByCategoryAndModelAndStatusAndConsentGrantedTrueOrderByPolicyVersionDesc(
            String category, String model, String status);
}
