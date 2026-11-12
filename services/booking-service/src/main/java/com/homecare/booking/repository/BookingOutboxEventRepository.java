package com.homecare.booking.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.booking.entity.BookingOutboxEventEntity;

public interface BookingOutboxEventRepository extends JpaRepository<BookingOutboxEventEntity, UUID> {
    List<BookingOutboxEventEntity> findByStatusOrderByCreatedAtAsc(String status);
}
