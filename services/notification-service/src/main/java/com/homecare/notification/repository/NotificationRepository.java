package com.homecare.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homecare.notification.entity.NotificationEntity;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    Optional<NotificationEntity> findByDedupeKey(String dedupeKey);
    List<NotificationEntity> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
}
