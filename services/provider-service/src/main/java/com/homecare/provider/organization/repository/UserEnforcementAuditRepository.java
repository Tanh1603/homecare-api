package com.homecare.provider.organization.repository;

import com.homecare.provider.organization.entity.UserEnforcementAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEnforcementAuditRepository extends JpaRepository<UserEnforcementAuditEntity, UUID> {
}
