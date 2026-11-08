package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.WorkUpdateEntity;

public interface WorkUpdateRepository extends JpaRepository<WorkUpdateEntity, UUID> {
    List<WorkUpdateEntity> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}
