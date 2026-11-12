package com.homecare.notification.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.notification.dto.NotificationDtos.DeviceRegistrationResponse;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.entity.DeviceRegistrationEntity;
import com.homecare.notification.entity.NotificationEntity;
import com.homecare.notification.entity.ProcessedEventEntity;
import com.homecare.notification.exception.NotificationErrorCode;
import com.homecare.notification.repository.DeviceRegistrationRepository;
import com.homecare.notification.repository.NotificationRepository;
import com.homecare.notification.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private static final Logger log = LoggerFactory.getLogger(NotificationInboxService.class);
    public static final String CONSUMER_NAME = "notification-inbox-consumer";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final DeviceRegistrationRepository deviceRegistrationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordIfAbsent(
            String eventId,
            String recipientId,
            String eventType,
            String title,
            String body) {
        recordIfAbsent(eventId, recipientId, eventType, title, body, Map.of());
    }

    @Transactional
    public void recordIfAbsent(
            String eventId,
            String recipientId,
            String eventType,
            String title,
            String body,
            Map<String, String> extraData) {
        if (recipientId == null || recipientId.isBlank()) {
            return;
        }
        String dedupeKey = eventId + ":" + recipientId;
        if (notificationRepository.findByDedupeKey(dedupeKey).isPresent()) {
            return;
        }
        String consumerKey = CONSUMER_NAME + ":" + recipientId;
        if (processedEventRepository.findByConsumerNameAndEventId(consumerKey, eventId).isPresent()) {
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        processedEventRepository.save(ProcessedEventEntity.builder()
                .id(UUID.randomUUID())
                .consumerName(consumerKey)
                .eventId(eventId)
                .eventType(eventType)
                .processedAt(now)
                .build());
        notificationRepository.save(NotificationEntity.builder()
                .id(UUID.randomUUID())
                .recipientId(recipientId)
                .dedupeKey(dedupeKey)
                .eventType(eventType)
                .title(title)
                .body(body)
                .read(false)
                .createdAt(now)
                .build());

        // Enqueue push notification dispatch message to dedicated RabbitMQ queue (decoupled from DB transaction)
        Map<String, String> data = new HashMap<>();
        if (extraData != null) {
            data.putAll(extraData);
        }
        if (eventType != null) {
            data.put("eventType", eventType);
        }
        if (eventId != null) {
            data.put("eventId", eventId);
        }

        // Only dispatch push to RabbitMQ after database transaction has successfully committed
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatchPushNotificationEvent(recipientId, title, body, data);
                        }
                    });
        } else {
            dispatchPushNotificationEvent(recipientId, title, body, data);
        }
    }

    private void dispatchPushNotificationEvent(
            String recipientId,
            String title,
            String body,
            Map<String, String> data) {
        try {
            Map<String, Object> pushPayload = new HashMap<>();
            pushPayload.put("recipientId", recipientId);
            pushPayload.put("title", title);
            pushPayload.put("body", body);
            pushPayload.put("data", data != null ? data : Map.of());
            String json = objectMapper.writeValueAsString(pushPayload);
            rabbitTemplate.convertAndSend(DomainEventConsumer.EXCHANGE, "notification.push.dispatch", json);
            log.debug("Enqueued push notification event to RabbitMQ for recipient={}", recipientId);
        } catch (Exception e) {
            log.warn("Could not enqueue push event to RabbitMQ for recipient={}: {}", recipientId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listInbox(String recipientId) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public NotificationResponse markAsRead(String recipientId, UUID notificationId) {
        NotificationEntity n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new DomainException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!n.getRecipientId().equals(recipientId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the recipient of this notification.");
        }
        if (!n.isRead()) {
            n.setRead(true);
            n.setReadAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            n = notificationRepository.saveAndFlush(n);
        }
        return toResponse(n);
    }

    @Transactional
    public DeviceRegistrationResponse registerDevice(String recipientId, RegisterDeviceRequest request) {
        Optional<DeviceRegistrationEntity> existing = deviceRegistrationRepository
                .findByPushToken(request.pushToken().trim());
        if (existing.isPresent()) {
            DeviceRegistrationEntity d = existing.get();
            // Transfer ownership to new user on shared devices / account switching
            d.setRecipientId(recipientId);
            d.setPlatform(request.platform().trim().toUpperCase());
            d.setActive(true);
            return toDeviceResponse(deviceRegistrationRepository.save(d));
        }
        DeviceRegistrationEntity saved = deviceRegistrationRepository.save(DeviceRegistrationEntity.builder()
                .id(UUID.randomUUID())
                .recipientId(recipientId)
                .platform(request.platform().trim().toUpperCase())
                .pushToken(request.pushToken().trim())
                .active(true)
                .createdAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .build());
        return toDeviceResponse(saved);
    }

    @Transactional
    public void deactivateDevice(String recipientId, UUID deviceId) {
        DeviceRegistrationEntity d = deviceRegistrationRepository.findById(deviceId)
                .orElseThrow(() -> new DomainException(NotificationErrorCode.DEVICE_REGISTRATION_NOT_FOUND));
        if (!d.getRecipientId().equals(recipientId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this device registration.");
        }
        d.setActive(false);
        deviceRegistrationRepository.save(d);
    }

    private NotificationResponse toResponse(NotificationEntity n) {
        return new NotificationResponse(
                n.getId(),
                n.getRecipientId(),
                n.getEventType(),
                n.getTitle(),
                n.getBody(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt());
    }

    private DeviceRegistrationResponse toDeviceResponse(DeviceRegistrationEntity d) {
        return new DeviceRegistrationResponse(
                d.getId(),
                d.getRecipientId(),
                d.getPlatform(),
                d.getPushToken(),
                d.isActive(),
                d.getCreatedAt());
    }
}
