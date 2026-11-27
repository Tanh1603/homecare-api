package com.homecare.provider.repository;

import com.homecare.provider.entity.TechnicianScheduleAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TechnicianScheduleAuditRepository extends JpaRepository<TechnicianScheduleAudit, UUID> {

    List<TechnicianScheduleAudit> findByTechnicianIdOrderByCreatedAtDesc(UUID technicianId);
}
