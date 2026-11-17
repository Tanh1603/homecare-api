package com.homecare.provider.organization.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.organization.entity.OrganizationMemberEntity;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMemberEntity, UUID> {

    Optional<OrganizationMemberEntity> findByOrganizationIdAndUserId(UUID organizationId, String userId);

    List<OrganizationMemberEntity> findByUserIdAndStatus(String userId, String status);

    List<OrganizationMemberEntity> findByOrganizationIdOrderByJoinedAtAsc(UUID organizationId);

    @Query("""
            SELECT COUNT(m) FROM OrganizationMemberEntity m
            JOIN OrganizationEntity o ON m.organizationId = o.id
            WHERE m.userId = :userId
              AND m.status = 'ACTIVE'
              AND o.status = com.homecare.provider.organization.entity.OrganizationStatus.APPROVED
            """)
    long countApprovedMembershipsByUserId(@Param("userId") String userId);
}
