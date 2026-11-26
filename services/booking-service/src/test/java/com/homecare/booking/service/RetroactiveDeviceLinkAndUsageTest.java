package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.grpc.BookingVerificationGrpcService;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.v1.HasDeviceBookingsRequest;
import com.homecare.booking.v1.HasDeviceBookingsResponse;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

import io.grpc.stub.StreamObserver;

@SpringBootTest
@ActiveProfiles("test")
class RetroactiveDeviceLinkAndUsageTest {

    @Autowired BookingLifecycleService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingOutboxEventRepository outboxRepository;
    @Autowired BookingVerificationGrpcService bookingGrpcService;

    @MockitoBean HouseholdVerificationClient householdVerificationClient;

    @Test
    void retroactiveLinkValidatesOwnershipStatusCardinalityArchivedAndEmitsBookingAcceptedOutbox() {
        String owner = "cust-" + UUID.randomUUID();
        String outsider = "outsider-" + UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        UUID archivedDevice = UUID.randomUUID();
        Instant originalAcceptedAt = Instant.parse("2026-11-24T18:30:00Z");

        BookingEntity unlinkedAccepted = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(owner)
                .serviceId(UUID.randomUUID())
                .serviceName("Vệ sinh điều hòa")
                .bookingType("MAINTENANCE")
                .purpose("MAINTENANCE")
                .deviceId(null)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plusSeconds(3600))
                .address("123 Test")
                .status(BookingStatus.ACCEPTED.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(300_000L)
                .acceptedAt(originalAcceptedAt)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        // Non-owner rejected (403)
        assertThatThrownBy(() -> bookingService.linkDeviceToAcceptedBooking(
                personalActor(outsider), unlinkedAccepted.getId(), deviceA))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        // ARCHIVED device rejected (409 DEVICE_ARCHIVED)
        when(householdVerificationClient.verifyDeviceOrFallback(eq(archivedDevice), eq(owner), any()))
                .thenThrow(new DomainException(BookingErrorCode.DEVICE_ARCHIVED));
        assertThatThrownBy(() -> bookingService.linkDeviceToAcceptedBooking(
                personalActor(owner), unlinkedAccepted.getId(), archivedDevice))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("DEVICE_ARCHIVED");

        // Success link to ACTIVE deviceA -> persists deviceId & emits booking.accepted.v1 with original acceptedAt
        when(householdVerificationClient.verifyDeviceOrFallback(eq(deviceA), eq(owner), any()))
                .thenReturn("AIR_CONDITIONER");
        var linked = bookingService.linkDeviceToAcceptedBooking(
                personalActor(owner), unlinkedAccepted.getId(), deviceA);
        assertThat(linked.deviceId()).isEqualTo(deviceA);

        List<?> acceptedEvents = outboxRepository.findAll().stream()
                .filter(e -> unlinkedAccepted.getId().equals(e.getAggregateId())
                        && DomainEventContracts.BOOKING_ACCEPTED.equals(e.getEventType()))
                .toList();
        assertThat(acceptedEvents).hasSize(1);
        assertThat(outboxRepository.findAll().stream()
                .filter(e -> unlinkedAccepted.getId().equals(e.getAggregateId()))
                .findFirst().orElseThrow().getPayloadJson())
                .contains("2026-11-24T18:30:00Z")
                .contains("\"retroactiveLink\":true");

        // Idempotent retry with same deviceA succeeds without emitting a second outbox event
        bookingService.linkDeviceToAcceptedBooking(personalActor(owner), unlinkedAccepted.getId(), deviceA);
        long eventCountAfterRetry = outboxRepository.findAll().stream()
                .filter(e -> unlinkedAccepted.getId().equals(e.getAggregateId())
                        && DomainEventContracts.BOOKING_ACCEPTED.equals(e.getEventType()))
                .count();
        assertThat(eventCountAfterRetry).isEqualTo(1);

        // Linking to a second deviceB violates 0..1 cardinality (BR-DEV-03) -> 409
        assertThatThrownBy(() -> bookingService.linkDeviceToAcceptedBooking(
                personalActor(owner), unlinkedAccepted.getId(), deviceB))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_ALREADY_LINKED_TO_DEVICE");

        // Non-ACCEPTED booking cannot be retroactively linked -> 409
        BookingEntity inProgress = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(owner)
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa tủ lạnh")
                .bookingType("STANDARD")
                .purpose("REPAIR")
                .deviceId(null)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plusSeconds(3600))
                .address("123 Test")
                .status(BookingStatus.IN_PROGRESS.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(200_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        assertThatThrownBy(() -> bookingService.linkDeviceToAcceptedBooking(
                personalActor(owner), inProgress.getId(), deviceA))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");

        // gRPC HasDeviceBookings returns true for linked deviceA and false for unused deviceB
        assertThat(callHasDeviceBookings(deviceA.toString()).getHasLinkedBookings()).isTrue();
        assertThat(callHasDeviceBookings(deviceB.toString()).getHasLinkedBookings()).isFalse();
        assertThat(callHasDeviceBookings("not-a-uuid").getHasLinkedBookings()).isTrue(); // fail-closed
    }

    private ActorContext personalActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null, Set.of("USER"), Set.of(), Set.of(), Set.of());
    }

    private HasDeviceBookingsResponse callHasDeviceBookings(String deviceId) {
        final HasDeviceBookingsResponse[] holder = new HasDeviceBookingsResponse[1];
        bookingGrpcService.hasDeviceBookings(
                HasDeviceBookingsRequest.newBuilder().setDeviceId(deviceId).build(),
                new StreamObserver<>() {
                    @Override public void onNext(HasDeviceBookingsResponse value) { holder[0] = value; }
                    @Override public void onError(Throwable t) { throw new AssertionError(t); }
                    @Override public void onCompleted() {}
                });
        return holder[0];
    }
}
