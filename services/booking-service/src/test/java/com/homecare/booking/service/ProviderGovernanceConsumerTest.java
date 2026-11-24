package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.common.event.DomainEventContracts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderGovernanceConsumerTest {

    private ServiceRequestRepository serviceRequestRepository;
    private BookingRepository bookingRepository;
    private ProviderGrpcClientAdapter providerGrpcClient;
    private ObjectMapper objectMapper;
    private ProviderGovernanceConsumer consumer;

    @BeforeEach
    void setUp() {
        serviceRequestRepository = mock(ServiceRequestRepository.class);
        bookingRepository = mock(BookingRepository.class);
        providerGrpcClient = mock(ProviderGrpcClientAdapter.class);
        objectMapper = new ObjectMapper();
        consumer = new ProviderGovernanceConsumer(
                serviceRequestRepository, bookingRepository, providerGrpcClient, objectMapper);
    }

    @Test
    void offeringDeactivatedMovesAwaitingProviderRequestsToAwaitingAlternative() throws Exception {
        UUID offeringId = UUID.randomUUID();
        ServiceRequestEntity sr = ServiceRequestEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("cust-1")
                .requestType("REPAIR")
                .serviceCategory("PLUMBING")
                .serviceOfferingId(offeringId)
                .status("AWAITING_PROVIDER")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(serviceRequestRepository.findByServiceOfferingIdAndStatusIn(offeringId, List.of("AWAITING_PROVIDER")))
                .thenReturn(List.of(sr));

        String payload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.OFFERING_DEACTIVATED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "serviceOfferingId", offeringId.toString(),
                        "reason", "PROVIDER_DEACTIVATED")));
        consumer.onGovernanceEvent(payload);

        assertThat(sr.getStatus()).isEqualTo("AWAITING_ALTERNATIVE");
        assertThat(sr.getFallbackReason()).isEqualTo("PROVIDER_DEACTIVATED");
        verify(serviceRequestRepository).save(sr);
    }

    @Test
    void orgSuspendedCancelsPreArrivedBookingsAtZeroFeeReleasesAllocationAndReopensSr() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID srId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        ServiceRequestEntity pendingSr = ServiceRequestEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("cust-1")
                .requestType("REPAIR")
                .serviceCategory("PLUMBING")
                .assignedProviderId(providerId)
                .status("QUOTATION_SENT")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ServiceRequestEntity linkedSr = ServiceRequestEntity.builder()
                .id(srId)
                .requesterProfileId("cust-2")
                .requestType("REPAIR")
                .serviceCategory("PLUMBING")
                .assignedProviderId(providerId)
                .status("BOOKED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        BookingEntity assignedBooking = BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("cust-2")
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa ống nước")
                .providerId(providerId)
                .serviceRequestId(srId)
                .activeAllocationOperationId(opId)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plusSeconds(3600))
                .address("123 Lê Lợi")
                .status(BookingStatus.ASSIGNED.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(300_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        BookingEntity inProgressBooking = BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("cust-3")
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa điện")
                .providerId(providerId)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plusSeconds(3600))
                .address("456 Hai Bà Trưng")
                .status(BookingStatus.IN_PROGRESS.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(500_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(serviceRequestRepository.findByAssignedProviderIdAndStatusIn(
                providerId, List.of("AWAITING_PROVIDER", "QUOTATION_SENT")))
                .thenReturn(List.of(pendingSr));
        when(bookingRepository.findByProviderIdAndStatusIn(
                providerId, List.of(BookingStatus.REQUESTED.name(), BookingStatus.ASSIGNED.name())))
                .thenReturn(List.of(assignedBooking));
        when(bookingRepository.findByProviderIdAndStatusIn(
                providerId, List.of(BookingStatus.IN_PROGRESS.name())))
                .thenReturn(List.of(inProgressBooking));
        when(serviceRequestRepository.findById(srId)).thenReturn(Optional.of(linkedSr));

        String payload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.ORG_SUSPENDED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "providerId", providerId.toString(),
                        "reason", "Safety violation",
                        "safetyInterventionFlag", true)));
        consumer.onGovernanceEvent(payload);

        assertThat(pendingSr.getStatus()).isEqualTo("AWAITING_ALTERNATIVE");
        assertThat(assignedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED.name());
        assertThat(assignedBooking.getQuotedPrice()).isZero();
        assertThat(assignedBooking.getAllocationStatus()).isEqualTo("RELEASED");
        assertThat(linkedSr.getStatus()).isEqualTo("AWAITING_ALTERNATIVE");
        assertThat(inProgressBooking.isAdminReviewFlagged()).isTrue();
        assertThat(inProgressBooking.getAdminReviewReason()).contains("SAFETY_INTERVENTION");
        verify(providerGrpcClient).releaseBooking(assignedBooking.getId(), opId, DomainEventContracts.ORG_SUSPENDED);
    }

    @Test
    void userSuspendedFlagsOpenBookingsForAdminReview() throws Exception {
        BookingEntity openBooking = BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("user-1")
                .serviceId(UUID.randomUUID())
                .serviceName("Bảo trì máy lạnh")
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plusSeconds(3600))
                .address("10 Nguyễn Huệ")
                .status(BookingStatus.IN_PROGRESS.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(250_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(bookingRepository.findOpenBookingsForUser("user-1", List.of(
                BookingStatus.REQUESTED.name(),
                BookingStatus.ASSIGNED.name(),
                BookingStatus.IN_PROGRESS.name(),
                "WAITING_FOR_PARTS",
                "COMPLETED_PENDING_ACCEPTANCE",
                "REVISION_REQUESTED")))
                .thenReturn(List.of(openBooking));

        String payload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.USER_SUSPENDED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "userId", "user-1",
                        "reason", "Fraud suspicion")));
        consumer.onGovernanceEvent(payload);

        assertThat(openBooking.isAdminReviewFlagged()).isTrue();
        assertThat(openBooking.getAdminReviewReason()).contains("USER_SUSPENDED");
        verify(bookingRepository).save(openBooking);
    }
}
