package com.homecare.provider.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.entity.TechnicianAllocation;

public interface TechnicianAllocationRepository extends JpaRepository<TechnicianAllocation, UUID> {
    Optional<TechnicianAllocation> findByIntentId(String intentId);

    @Query("""
            select a from TechnicianAllocation a
            where a.technicianId = :techId
              and a.status = 'ALLOCATED'
              and a.startsAt < :reqEnd
              and a.endsAt > :reqStart
            """)
    List<TechnicianAllocation> findActiveOverlapping(
            @Param("techId") UUID techId,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);
}
