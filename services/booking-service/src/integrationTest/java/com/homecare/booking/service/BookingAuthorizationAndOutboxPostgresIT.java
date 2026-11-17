package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class BookingAuthorizationAndOutboxPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    BookingLifecycleService service;

    @Autowired
    BookingOutboxEventRepository outboxRepository;

    @MockitoBean
    ProviderGrpcClientAdapter providerGrpcClient;

    @MockitoBean
    HouseholdVerificationClient householdVerificationClient;

    @Test
    void enforcesPaginationIsolationAndSkipLockedOutboxOnPostgres() {
        UUID serviceId = UUID.randomUUID();
        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), anyString())).thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId)).thenReturn(VerifyOfferingResponse.newBuilder()
                .setServiceId(serviceId.toString()).setName("AC Clean").setBasePrice(300_000L)
                .setActive(true).setCategory("AC").setEstimatedDurationMinutes(60).build());
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder().setAllocated(false).setFailureReason("BUSY").build());

        service.createBooking("pg-buyer-a", new CreateBookingRequest(
                serviceId, null, "AC", Instant.now().plus(2, ChronoUnit.HOURS), null, "Addr A", null));
        service.createBooking("pg-buyer-b", new CreateBookingRequest(
                serviceId, null, "AC", Instant.now().plus(3, ChronoUnit.HOURS), null, "Addr B", null));

        ActorContext buyerA = new ActorContext("pg-buyer-a", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of());
        var page = service.listBookingsPage(buyerA, 1, 20, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pagination().totalItems()).isEqualTo(1);
        assertThat(outboxRepository.findPendingDueEvents(Instant.now().plusSeconds(60))).isNotEmpty();
    }
}
