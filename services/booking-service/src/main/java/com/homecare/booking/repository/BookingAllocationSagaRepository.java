package com.homecare.booking.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingAllocationSagaEntity;

public interface BookingAllocationSagaRepository extends JpaRepository<BookingAllocationSagaEntity, UUID> {
    Optional<BookingAllocationSagaEntity> findByBookingId(UUID bookingId);
}
