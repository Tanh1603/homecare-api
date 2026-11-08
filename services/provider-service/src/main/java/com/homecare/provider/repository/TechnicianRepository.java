package com.homecare.provider.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.provider.entity.Technician;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByProfileId(String profileId);
    List<Technician> findByProviderIdAndStatus(UUID providerId, String status);
    List<Technician> findByStatus(String status);
}
