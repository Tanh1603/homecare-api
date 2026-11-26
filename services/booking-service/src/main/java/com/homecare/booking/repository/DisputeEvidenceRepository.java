package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.DisputeEvidenceEntity;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidenceEntity, UUID> {
    List<DisputeEvidenceEntity> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);
}
