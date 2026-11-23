package com.homecare.provider.organization.repository;

import com.homecare.provider.organization.entity.OrganizationEnforcementEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationEnforcementRepository extends JpaRepository<OrganizationEnforcementEntity, UUID> {

    List<OrganizationEnforcementEntity> findByOrganizationIdOrderByIssuedAtDesc(UUID organizationId);
}
