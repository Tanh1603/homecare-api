package com.homecare.commerce.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.PartCompatibilityEntity;

public interface PartCompatibilityRepository extends JpaRepository<PartCompatibilityEntity, UUID> {
    List<PartCompatibilityEntity> findByPartProductId(UUID partProductId);
}
