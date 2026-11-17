package com.homecare.provider.organization.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;

public interface ProviderOutboxEventRepository extends JpaRepository<ProviderOutboxEventEntity, UUID> {

    @Query(value = """
            SELECT * FROM provider_outbox_events
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ProviderOutboxEventEntity> findPendingDueEvents(@Param("now") Instant now);
}
