package com.homecare.commerce.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import com.homecare.commerce.entity.InstallationRefundEntity;
import com.homecare.commerce.repository.InstallationRefundRepository;
import com.homecare.commerce.repository.OrderComponentSnapshotRepository;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class InstallationCancellationConsumer {
    public static final String QUEUE = "commerce.installation.cancellation.q";

    private final InstallationRefundRepository refundRepository;
    private final OrderComponentSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @Transactional
    @SuppressWarnings("unchecked")
    public void onDomainEvent(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            if (!"INSTALLATION_CANCELLED".equals(
                    String.valueOf(root.getOrDefault(DomainEventContracts.FIELD_EVENT_TYPE, "")))) {
                return;
            }
            UUID eventId = UUID.fromString(String.valueOf(root.get(DomainEventContracts.FIELD_EVENT_ID)));
            if (refundRepository.findByEventId(eventId).isPresent()) {
                return;
            }
            Map<String, Object> data = root.get(DomainEventContracts.FIELD_DATA) instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            UUID orderId = UUID.fromString(String.valueOf(data.get("orderId")));
            UUID orderLineId = UUID.fromString(String.valueOf(data.get("orderLineId")));
            var snapshot = snapshotRepository.lockByOrderLineIdAndComponentType(orderLineId, "INSTALLATION")
                    .orElseThrow(() -> new IllegalStateException("Installation component snapshot not found"));
            long refundAmount = snapshot.getRefundableRemainingAmount();
            snapshot.setRefundableRemainingAmount(0L);
            snapshotRepository.save(snapshot);
            refundRepository.save(InstallationRefundEntity.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .orderId(orderId)
                    .orderLineId(orderLineId)
                    .amount(refundAmount)
                    .status("MANUAL_PENDING")
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to process installation cancellation", ex);
        }
    }
}
