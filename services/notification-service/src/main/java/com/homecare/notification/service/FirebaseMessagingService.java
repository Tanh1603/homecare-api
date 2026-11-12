package com.homecare.notification.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.homecare.notification.entity.DeviceRegistrationEntity;
import com.homecare.notification.repository.DeviceRegistrationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FirebaseMessagingService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingService.class);

    private final DeviceRegistrationRepository deviceRegistrationRepository;

    public int sendPushToUser(
            String recipientId,
            String title,
            String body,
            Map<String, String> data) {
        if (recipientId == null || recipientId.isBlank()) {
            return 0;
        }

        List<DeviceRegistrationEntity> devices =
                deviceRegistrationRepository.findByRecipientIdAndActiveTrue(recipientId);

        if (devices.isEmpty()) {
            log.debug("No active FCM device tokens for user {}; skipping FCM push", recipientId);
            return 0;
        }

        int successCount = 0;
        for (DeviceRegistrationEntity device : devices) {
            boolean dispatched = dispatchFcmMessage(device.getPushToken(), device.getPlatform(), title, body, data);
            if (dispatched) {
                successCount++;
            }
        }

        log.info("FCM push triggered for user {}: {}/{} devices notified [title='{}']",
                recipientId, successCount, devices.size(), title);
        return successCount;
    }

    private boolean dispatchFcmMessage(
            String fcmToken,
            String platform,
            String title,
            String body,
            Map<String, String> data) {

        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("FirebaseApp not initialized (no credentials). Skipping push dispatch to token={}",
                    fcmToken.length() > 10 ? fcmToken.substring(0, 10) + "***" : fcmToken);
            return false;
        }

        try {
            Notification notification = Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(notification);

            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setChannelId("homecare_notifications")
                            .setSound("default")
                            .build())
                    .build();
            messageBuilder.setAndroidConfig(androidConfig);

            String response = FirebaseMessaging.getInstance().send(messageBuilder.build());
            log.info("[FCM-REAL] Dispatched push to token={}, messageId={}",
                    fcmToken.length() > 10 ? fcmToken.substring(0, 10) + "***" : fcmToken, response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("[FCM-ERROR] Failed to send push to token={}: {} (code: {})",
                    fcmToken.length() > 10 ? fcmToken.substring(0, 10) + "***" : fcmToken,
                    e.getMessage(), e.getMessagingErrorCode());

            if (e.getMessagingErrorCode() != null) {
                String code = e.getMessagingErrorCode().name();
                if ("UNREGISTERED".equals(code) || "INVALID_ARGUMENT".equals(code)) {
                    log.warn("Deactivating dead FCM device token={}", fcmToken);
                    deviceRegistrationRepository.findByPushToken(fcmToken).ifPresent(dev -> {
                        dev.setActive(false);
                        deviceRegistrationRepository.save(dev);
                    });
                }
            }
            return false;
        } catch (Exception e) {
            log.error("[FCM-ERROR] Unexpected error sending FCM push: {}", e.getMessage(), e);
            return false;
        }
    }
}
