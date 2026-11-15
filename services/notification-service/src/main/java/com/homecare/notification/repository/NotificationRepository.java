package com.homecare.notification.repository;

import com.homecare.notification.entity.NotificationEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    long countByRecipientIdAndReadFalse(String recipientId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationEntity n set n.read = true, n.readAt = :now where n.recipientId = :recipientId and n.read = false")
    int markAllRead(String recipientId, Instant now);
    Optional<NotificationEntity> findByDedupeKey(String dedupeKey);
    List<NotificationEntity> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
}
