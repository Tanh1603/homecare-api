package com.homecare.provider.organization.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    List<OrganizationEntity> findByStatusOrderByCreatedAtDesc(OrganizationStatus status);

    List<OrganizationEntity> findAllByOrderByCreatedAtDesc();
}
