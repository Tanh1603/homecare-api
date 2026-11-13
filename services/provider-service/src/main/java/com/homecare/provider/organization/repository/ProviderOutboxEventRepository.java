package com.homecare.provider.organization.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;

public interface ProviderOutboxEventRepository extends JpaRepository<ProviderOutboxEventEntity, UUID> {

    @Query("""
            SELECT e FROM ProviderOutboxEventEntity e
            WHERE e.status = 'PENDING'
              AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
            ORDER BY e.createdAt ASC
            """)
    List<ProviderOutboxEventEntity> findPendingDueEvents(@Param("now") Instant now);
}
