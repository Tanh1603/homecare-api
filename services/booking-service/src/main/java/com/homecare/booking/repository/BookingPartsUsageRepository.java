package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingPartsUsageEntity;

public interface BookingPartsUsageRepository extends JpaRepository<BookingPartsUsageEntity, UUID> {

    List<BookingPartsUsageEntity> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    boolean existsByPartsRequestId(UUID partsRequestId);
}
