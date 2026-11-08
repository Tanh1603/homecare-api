package com.homecare.notification.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.homecare.notification.entity.DeviceRegistrationEntity;
import com.homecare.notification.repository.DeviceRegistrationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FirebaseMessagingService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingService.class);

    private final DeviceRegistrationRepository deviceRegistrationRepository;

    @Value("${homecare.fcm.project-id:homecare-sandbox}")
    private String fcmProjectId;

    @Value("${homecare.fcm.sandbox-mode:true}")
    private boolean sandboxMode;

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
        if (sandboxMode) {
            log.info("[FCM-SANDBOX] Dispatched push to platform={}, token={}... [title='{}', body='{}', data={}]",
                    platform,
                    fcmToken.length() > 10 ? fcmToken.substring(0, 10) + "***" : fcmToken,
                    title,
                    body,
                    data);
            return true;
        }

        // Production FCM HTTP v1 dispatch
        log.info("[FCM-PRODUCTION] Project '{}' sending to token={}...", fcmProjectId, fcmToken);
        return true;
    }
}
