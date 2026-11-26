package com.homecare.household.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.household.entity.MaintenancePolicyEntity;

public interface MaintenancePolicyRepository extends JpaRepository<MaintenancePolicyEntity, UUID> {
    Optional<MaintenancePolicyEntity> findFirstByCategoryAndModelAndStatusAndConsentGrantedTrueOrderByPolicyVersionDesc(
            String category, String model, String status);

    Optional<MaintenancePolicyEntity> findFirstByCategoryAndModelAndStatusOrderByPolicyVersionDesc(
            String category, String model, String status);

    Optional<MaintenancePolicyEntity> findFirstByCategoryAndModelIsNullAndStatusOrderByPolicyVersionDesc(
            String category, String status);

    @Query("""
            SELECT COALESCE(MAX(p.policyVersion), 0)
            FROM MaintenancePolicyEntity p
            WHERE p.category = :category
              AND ((:model IS NULL AND p.model IS NULL) OR p.model = :model)
            """)
    int findMaxPolicyVersion(@Param("category") String category, @Param("model") String model);

    @Query("""
            SELECT p FROM MaintenancePolicyEntity p
            WHERE (:category IS NULL OR p.category = :category)
              AND (:status IS NULL OR p.status = :status)
            ORDER BY p.category ASC, p.model ASC NULLS FIRST, p.policyVersion DESC
            """)
    Page<MaintenancePolicyEntity> searchForAdmin(
            @Param("category") String category,
            @Param("status") String status,
            Pageable pageable);
}
