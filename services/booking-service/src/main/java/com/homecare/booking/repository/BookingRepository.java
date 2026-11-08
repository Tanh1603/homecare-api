package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingEntity;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
    List<BookingEntity> findByRequesterProfileIdOrderByCreatedAtDesc(String requesterProfileId);
    List<BookingEntity> findAllByOrderByCreatedAtDesc();
}
