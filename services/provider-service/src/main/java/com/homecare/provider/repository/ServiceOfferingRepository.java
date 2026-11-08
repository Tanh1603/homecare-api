package com.homecare.provider.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.entity.ServiceOffering;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID> {
    List<ServiceOffering> findByActiveTrueOrderByCreatedAtDesc();
    List<ServiceOffering> findByProviderIdAndActiveTrue(UUID providerId);
}
