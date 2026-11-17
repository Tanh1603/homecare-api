package com.homecare.household.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.household.entity.HouseholdOutboxEvent;

public interface HouseholdOutboxEventRepository extends JpaRepository<HouseholdOutboxEvent, UUID> {
    List<HouseholdOutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    @Query(value = """
            SELECT * FROM household_outbox_events
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<HouseholdOutboxEvent> findPendingDueEvents(@Param("now") Instant now);
}
