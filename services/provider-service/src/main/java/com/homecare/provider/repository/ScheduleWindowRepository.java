package com.homecare.provider.repository;

import com.homecare.provider.entity.ScheduleWindow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleWindowRepository extends JpaRepository<ScheduleWindow, UUID> {
    List<ScheduleWindow> findByTechnicianIdOrderByStartsAtAsc(UUID technicianId);
    List<ScheduleWindow> findByAllocatedBookingId(UUID bookingId);

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
            order by w.startsAt asc
            """)
    List<ScheduleWindow> findOverlapping(
            @Param("techId") UUID techId,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);

    @Query("""
            select w from ScheduleWindow w
            where w.technicianId in :techIds
              and w.startsAt < :reqEnd
              and w.endsAt > :reqStart
            order by w.startsAt asc
            """)
    List<ScheduleWindow> findOverlappingForTechnicians(
            @Param("techIds") Collection<UUID> techIds,
            @Param("reqStart") Instant reqStart,
            @Param("reqEnd") Instant reqEnd);

    @Query("""
            select w from ScheduleWindow w
            where w.technicianId = :techId
              and (w.status = 'BOOKED' or w.allocatedBookingId is not null)
              and w.endsAt > :fromInstant
            order by w.startsAt asc
            """)
    List<ScheduleWindow> findBookedEndingAfter(
            @Param("techId") UUID techId,
            @Param("fromInstant") Instant fromInstant);
}
