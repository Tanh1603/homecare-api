package com.homecare.commerce.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.commerce.entity.InstallationRefundEntity;

public interface InstallationRefundRepository extends JpaRepository<InstallationRefundEntity, UUID> {
    Optional<InstallationRefundEntity> findByEventId(UUID eventId);
}
