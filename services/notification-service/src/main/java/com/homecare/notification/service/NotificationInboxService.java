package com.homecare.notification.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.DomainException;
import com.homecare.notification.dto.NotificationDtos.DeviceRegistrationResponse;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.entity.DeviceRegistrationEntity;
import com.homecare.notification.entity.NotificationEntity;
import com.homecare.notification.entity.ProcessedEventEntity;
import com.homecare.notification.repository.DeviceRegistrationRepository;
import com.homecare.notification.repository.NotificationRepository;
import com.homecare.notification.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    public static final String CONSUMER_NAME = "notification-inbox-consumer";

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final DeviceRegistrationRepository deviceRegistrationRepository;
    private final FirebaseMessagingService firebaseMessagingService;

    @Transactional
    public void recordIfAbsent(
            String eventId,
            String recipientId,
            String eventType,
            String title,
            String body) {
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

        // Trigger FCM push notification to user's registered devices (wake-up ping for inbox synchronization)
        java.util.Map<String, String> data = new java.util.HashMap<>();
        if (eventType != null) {
            data.put("eventType", eventType);
        }
        if (eventId != null) {
            data.put("eventId", eventId);
        }
        firebaseMessagingService.sendPushToUser(recipientId, title, body, data);
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
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found."));
        if (!n.getRecipientId().equals(recipientId)) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not the recipient of this notification.");
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
            if (!d.getRecipientId().equals(recipientId)) {
                throw new DomainException(
                        HttpStatus.CONFLICT,
                        "TOKEN_ALREADY_REGISTERED",
                        "Push token is already registered to another user.");
            }
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
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "DEVICE_REGISTRATION_NOT_FOUND", "Device registration not found."));
        if (!d.getRecipientId().equals(recipientId)) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not the owner of this device registration.");
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
