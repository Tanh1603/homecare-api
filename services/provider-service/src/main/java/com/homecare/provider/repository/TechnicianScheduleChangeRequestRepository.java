package com.homecare.provider.repository;

import com.homecare.provider.entity.ScheduleChangeRequestStatus;
import com.homecare.provider.entity.TechnicianScheduleChangeRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianScheduleChangeRequestRepository
        extends JpaRepository<TechnicianScheduleChangeRequest, UUID> {

    List<TechnicianScheduleChangeRequest> findByTechnicianIdOrderByCreatedAtDesc(UUID technicianId);

    List<TechnicianScheduleChangeRequest> findByTechnicianIdAndStatusOrderByCreatedAtDesc(
            UUID technicianId, ScheduleChangeRequestStatus status);

    List<TechnicianScheduleChangeRequest> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<TechnicianScheduleChangeRequest> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, ScheduleChangeRequestStatus status);

    @Query("""
            select case when count(r) > 0 then true else false end
            from TechnicianScheduleChangeRequest r
            where r.technicianId = :technicianId
              and r.status = com.homecare.provider.entity.ScheduleChangeRequestStatus.PENDING
              and r.fromDate <= :toDate
              and r.toDate >= :fromDate
            """)
    boolean existsOverlappingPending(
            @Param("technicianId") UUID technicianId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
