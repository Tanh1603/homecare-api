package com.homecare.commerce.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.commerce.entity.CommerceOutboxEventEntity;

public interface CommerceOutboxEventRepository extends JpaRepository<CommerceOutboxEventEntity, UUID> {
    @Query(value = """
            SELECT * FROM commerce_outbox_events
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<CommerceOutboxEventEntity> findPendingDueEvents(@Param("now") Instant now);
}
