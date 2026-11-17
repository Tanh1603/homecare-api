package com.homecare.booking.repository;

import com.homecare.booking.entity.WorkEvidenceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkEvidenceRepository extends JpaRepository<WorkEvidenceEntity, UUID> {
    List<WorkEvidenceEntity> findByIdInAndBookingId(List<UUID> ids, UUID bookingId);
    List<WorkEvidenceEntity> findByWorkUpdateIdIn(List<UUID> workUpdateIds);
}
