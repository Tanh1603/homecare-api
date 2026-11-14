package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingEntity;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
    List<BookingEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId);
    List<BookingEntity> findAllByOrderByCreatedAtDesc();

    Page<BookingEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId, Pageable pageable);
    Page<BookingEntity> findByAssignedTechnicianProfileIdOrderByCreatedAtDesc(String assignedTechnicianProfileId, Pageable pageable);
    Page<BookingEntity> findByProviderIdOrderByCreatedAtDesc(UUID providerId, Pageable pageable);
    Page<BookingEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
