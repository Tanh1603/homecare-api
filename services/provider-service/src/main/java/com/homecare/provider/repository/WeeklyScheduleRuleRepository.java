package com.homecare.provider.repository;

import com.homecare.provider.entity.WeeklyScheduleRule;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WeeklyScheduleRuleRepository extends JpaRepository<WeeklyScheduleRule, UUID> {

    List<WeeklyScheduleRule> findByTechnicianIdOrderByEffectiveFromAsc(UUID technicianId);

    List<WeeklyScheduleRule> findByTechnicianIdInOrderByEffectiveFromAsc(Collection<UUID> technicianIds);

    @Query("""
            select distinct r.technicianId from WeeklyScheduleRule r
            where r.effectiveUntil is null or r.effectiveUntil >= :fromDate
            """)
    List<UUID> findDistinctTechnicianIdsWithActiveOrUpcomingRules(@Param("fromDate") LocalDate fromDate);
}
