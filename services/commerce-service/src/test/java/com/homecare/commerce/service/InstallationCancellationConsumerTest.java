package com.homecare.commerce.service;

import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homecare.commerce.entity.InstallationRefundEntity;
import com.homecare.commerce.entity.OrderComponentSnapshotEntity;
import com.homecare.commerce.repository.InstallationRefundRepository;
import com.homecare.commerce.repository.OrderComponentSnapshotRepository;

import tools.jackson.databind.ObjectMapper;

class InstallationCancellationConsumerTest {

    @Test
    void processesEachCancellationEventOnlyOnce() {
        InstallationRefundRepository refunds = mock(InstallationRefundRepository.class);
        OrderComponentSnapshotRepository snapshots = mock(OrderComponentSnapshotRepository.class);
        InstallationCancellationConsumer consumer = new InstallationCancellationConsumer(
                refunds,
                snapshots,
                new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        OrderComponentSnapshotEntity snapshot = OrderComponentSnapshotEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .orderLineId(orderLineId)
                .componentType("INSTALLATION")
                .allocatedGrossAmount(350_000)
                .allocatedDiscountAmount(0)
                .refundableRemainingAmount(350_000)
                .policyVersion("MVP-2")
                .build();
        when(refunds.findByEventId(eventId))
                .thenReturn(Optional.empty(), Optional.of(InstallationRefundEntity.builder().build()));
        when(snapshots.lockByOrderLineIdAndComponentType(orderLineId, "INSTALLATION"))
                .thenReturn(Optional.of(snapshot));

        String payload = """
                {"eventId":"%s","eventType":"INSTALLATION_CANCELLED","data":{"orderId":"%s","orderLineId":"%s"}}
                """.formatted(eventId, orderId, orderLineId);

        consumer.onDomainEvent(payload);
        consumer.onDomainEvent(payload);

        verify(snapshots, times(1)).save(snapshot);
        verify(refunds, times(1)).save(any(InstallationRefundEntity.class));
        org.assertj.core.api.Assertions.assertThat(snapshot.getRefundableRemainingAmount()).isZero();
    }
}
