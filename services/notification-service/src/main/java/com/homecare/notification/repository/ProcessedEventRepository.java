package com.homecare.notification.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.notification.entity.ProcessedEventEntity;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {
    Optional<ProcessedEventEntity> findByConsumerNameAndEventId(String consumerName, String eventId);
}
