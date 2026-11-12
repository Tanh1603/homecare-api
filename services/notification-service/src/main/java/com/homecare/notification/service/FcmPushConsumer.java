package com.homecare.notification.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class FcmPushConsumer {

    private static final Logger log = LoggerFactory.getLogger(FcmPushConsumer.class);
    public static final String QUEUE = "notification.push.dispatch.q";

    private final FirebaseMessagingService firebaseMessagingService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onPushDispatch(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            String recipientId = (String) root.get("recipientId");
            String title = (String) root.get("title");
            String body = (String) root.get("body");
            Map<String, String> data = root.get("data") instanceof Map<?, ?> m
                    ? (Map<String, String>) m
                    : Map.of();

            log.debug("Received push dispatch request from RabbitMQ for recipient={}", recipientId);
            firebaseMessagingService.sendPushToUser(recipientId, title, body, data);
        } catch (Exception e) {
            log.error("Failed to process push dispatch message: {}", e.getMessage(), e);
        }
    }
}
