package com.homecare.provider.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.entity.ProviderEntity;

public interface ProviderRepository extends JpaRepository<ProviderEntity, UUID> {

    Optional<ProviderEntity> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);
}
