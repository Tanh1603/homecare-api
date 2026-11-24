package com.homecare.provider.organization.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    List<OrganizationEntity> findByStatusOrderByCreatedAtDesc(OrganizationStatus status);

    List<OrganizationEntity> findAllByOrderByCreatedAtDesc();

    @Query("""
            SELECT o FROM OrganizationEntity o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:legalEntityType IS NULL OR o.legalEntityType = :legalEntityType)
              AND (:searchPattern IS NULL
                   OR LOWER(o.name) LIKE :searchPattern
                   OR LOWER(COALESCE(o.taxCode, '')) LIKE :searchPattern
                   OR LOWER(COALESCE(o.city, '')) LIKE :searchPattern
                   OR LOWER(COALESCE(o.phone, '')) LIKE :searchPattern)
            """)
    Page<OrganizationEntity> searchForAdmin(
            @Param("status") OrganizationStatus status,
            @Param("legalEntityType") LegalEntityType legalEntityType,
            @Param("searchPattern") String searchPattern,
            Pageable pageable);

    boolean existsByOwnerUserIdAndStatus(String ownerUserId, OrganizationStatus status);

    List<OrganizationEntity> findByOwnerUserIdAndStatus(String ownerUserId, OrganizationStatus status);
}
