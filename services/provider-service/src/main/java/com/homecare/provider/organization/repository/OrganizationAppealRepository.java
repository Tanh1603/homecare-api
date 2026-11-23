package com.homecare.provider.organization.repository;

import com.homecare.provider.organization.entity.OrganizationAppealEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationAppealRepository extends JpaRepository<OrganizationAppealEntity, UUID> {

    List<OrganizationAppealEntity> findByOrganizationIdOrderBySubmittedAtDesc(UUID organizationId);

    long countByEnforcementId(UUID enforcementId);
}
