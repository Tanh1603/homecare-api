package com.homecare.booking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homecare.booking.entity.BookingPartsRequestEntity;

import jakarta.persistence.LockModeType;

public interface BookingPartsRequestRepository extends JpaRepository<BookingPartsRequestEntity, UUID> {

    List<BookingPartsRequestEntity> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BookingPartsRequestEntity r WHERE r.id = :id")
    Optional<BookingPartsRequestEntity> lockById(@Param("id") UUID id);

    boolean existsByBookingIdAndStatusIn(UUID bookingId, List<String> statuses);
}
