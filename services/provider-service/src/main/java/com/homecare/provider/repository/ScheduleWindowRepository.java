package com.homecare.provider.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.provider.entity.ScheduleWindow;

public interface ScheduleWindowRepository extends JpaRepository<ScheduleWindow, UUID> {
    List<ScheduleWindow> findByTechnicianIdOrderByStartsAtAsc(UUID technicianId);

    @Query("""
            select w from ScheduleWindow w
            where w.technicianId = :techId
              and w.status = 'AVAILABLE'
              and w.startsAt <= :reqStart
              and w.endsAt >= :reqEnd
            """)
    List<ScheduleWindow> findAvailableCovering(
            @Param("techId") UUID techId,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);

    @Query("""
            select w from ScheduleWindow w
            where w.technicianId = :techId
              and w.startsAt < :reqEnd
              and w.endsAt > :reqStart
            """)
    List<ScheduleWindow> findOverlapping(
            @Param("techId") UUID techId,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);
}
