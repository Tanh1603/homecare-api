package com.homecare.provider.repository;

import com.homecare.provider.entity.ScheduleException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleExceptionRepository extends JpaRepository<ScheduleException, UUID> {

    List<ScheduleException> findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
            UUID technicianId, LocalDate fromDate, LocalDate toDate);

    List<ScheduleException> findByTechnicianIdInAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
            Collection<UUID> technicianIds, LocalDate fromDate, LocalDate toDate);

    List<ScheduleException> findByTechnicianIdAndLocalDateOrderByStartTimeAsc(
            UUID technicianId, LocalDate localDate);

    @Query("""
            select distinct e.technicianId from ScheduleException e
            where e.localDate between :fromDate and :toDate
            """)
    List<UUID> findDistinctTechnicianIdsWithExceptionsBetween(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    void deleteByTechnicianIdAndLocalDate(UUID technicianId, LocalDate localDate);
}
