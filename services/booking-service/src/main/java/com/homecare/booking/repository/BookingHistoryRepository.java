package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingHistoryEntry;

public interface BookingHistoryRepository extends JpaRepository<BookingHistoryEntry, UUID> {
    List<BookingHistoryEntry> findByBookingIdOrderByOccurredAtAsc(UUID bookingId);
}
