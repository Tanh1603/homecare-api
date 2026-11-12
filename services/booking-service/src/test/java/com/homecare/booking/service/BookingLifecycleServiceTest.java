package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.WarrantyVerificationResult;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.entity.BookingAllocationSagaEntity;
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;

@SpringBootTest
@ActiveProfiles("test")
class BookingLifecycleServiceTest {

    @Autowired
    private BookingLifecycleService service;

    @Autowired
    private BookingAllocationSagaRepository sagaRepository;

    @Autowired
    private BookingOutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProviderGrpcClientAdapter providerGrpcClient;

    @MockitoBean
    private HouseholdVerificationClient householdVerificationClient;

    @Test
    void executesImmediateAutoDispatchSagaPersistsOutboxAndEnforcesStateMachine() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333301");
        UUID providerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");

        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), any()))
                .thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(providerId.toString())
                        .setName("Vệ sinh Máy lạnh")
                        .setBasePrice(250_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(60)
                        .build());
        when(providerGrpcClient.allocateTechnician(anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(techId.toString())
                        .setTechnicianProfileId("demo-technician")
                        .setTechnicianName("Trần Văn Kỹ Thuật")
                        .setProviderId(providerId.toString())
                        .build());

        BookingResponse created = service.createBooking(
                "customer-sub-1",
                new CreateBookingRequest(
                        serviceId,
                        UUID.randomUUID(),
                        "AC",
                        Instant.now().plus(2, ChronoUnit.HOURS),
                        null,
                        "123 Lê Lợi, Q1",
                        "Máy lạnh chảy nước"));

        assertThat(created.status()).isEqualTo("ASSIGNED");
        assertThat(created.allocationStatus()).isEqualTo("ALLOCATED");
        assertThat(created.assignedTechnicianId()).isEqualTo(techId);
        assertThat(created.bookingType()).isEqualTo("STANDARD");
        assertThat(created.quotedPrice()).isEqualTo(250_000L);

        BookingAllocationSagaEntity saga = sagaRepository.findByBookingId(created.id()).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo("ALLOCATED");
        assertThat(saga.getCurrentStep()).isEqualTo("COMPLETED");
        assertThat(outboxEventRepository.findAll()).isNotEmpty();

        BookingResponse inProgress = service.appendWorkUpdate(
                "demo-technician",
                created.id(),
                EtagSupport.format(created.version()),
                new CreateWorkUpdateRequest("STARTED", "Đã tới nhà khách hàng"));
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");

        assertThatThrownBy(() -> service.cancelBooking(
                "customer-sub-1",
                false,
                created.id(),
                EtagSupport.format(inProgress.version()),
                new CancelBookingRequest("Đổi ý")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");

        BookingResponse completed = service.appendWorkUpdate(
                "demo-technician",
                created.id(),
                EtagSupport.format(inProgress.version()),
                new CreateWorkUpdateRequest("COMPLETED", "Đã vệ sinh và nạp gas đầy đủ"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(service.verifyReviewEligibility(created.id(), "customer-sub-1").eligible()).isTrue();
    }

    @Test
    void enforcesWarrantyClaimZeroPriceAndStrictOrganizationLockOrRejectsExpiredWarranty() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333302");
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");
        UUID activeWarrantyId = UUID.randomUUID();
        UUID expiredWarrantyId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        when(householdVerificationClient.verifyWarranty(eq(activeWarrantyId), eq("customer-sub-w")))
                .thenReturn(new WarrantyVerificationResult(
                        activeWarrantyId, deviceId, "AC", orgId, "Điện Lạnh Bách Khoa Sài Gòn"));
        when(householdVerificationClient.verifyWarranty(eq(expiredWarrantyId), eq("customer-sub-w")))
                .thenThrow(new DomainException(
                        HttpStatus.CONFLICT,
                        "WARRANTY_EXPIRED",
                        "Warranty has expired; please book a paid repair service instead."));

        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(orgId.toString())
                        .setName("Sửa chữa Máy lạnh Inverter")
                        .setBasePrice(450_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(90)
                        .build());
        when(providerGrpcClient.allocateTechnician(
                anyString(), any(), eq(serviceId), eq("AC"), any(), any(), eq(orgId), eq(true)))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(techId.toString())
                        .setTechnicianProfileId("demo-technician")
                        .setTechnicianName("Trần Văn Kỹ Thuật")
                        .setProviderId(orgId.toString())
                        .build());

        BookingResponse warrantyBooking = service.createBooking(
                "customer-sub-w",
                new CreateBookingRequest(
                        serviceId,
                        deviceId,
                        "AC",
                        Instant.now().plus(3, ChronoUnit.HOURS),
                        null,
                        "12 Nguyễn Huệ, Q1",
                        "Bảo hành bo mạch máy lạnh",
                        "WARRANTY_CLAIM",
                        activeWarrantyId,
                        orgId));

        assertThat(warrantyBooking.bookingType()).isEqualTo("WARRANTY_CLAIM");
        assertThat(warrantyBooking.warrantyId()).isEqualTo(activeWarrantyId);
        assertThat(warrantyBooking.quotedPrice()).isZero();
        assertThat(warrantyBooking.providerId()).isEqualTo(orgId);
        assertThat(warrantyBooking.status()).isEqualTo("ASSIGNED");

        assertThatThrownBy(() -> service.createBooking(
                "customer-sub-w",
                new CreateBookingRequest(
                        serviceId,
                        deviceId,
                        "AC",
                        Instant.now().plus(3, ChronoUnit.HOURS),
                        null,
                        "12 Nguyễn Huệ, Q1",
                        "Bảo hành thẻ đã hết hạn",
                        "WARRANTY_CLAIM",
                        expiredWarrantyId,
                        orgId)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("WARRANTY_EXPIRED");
    }

    @Test
    void degradesGracefullyToRequestedWhenNoTechnicianAvailable() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333302");
        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), any()))
                .thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setName("Sửa Máy lạnh")
                        .setBasePrice(450_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(90)
                        .build());
        when(providerGrpcClient.allocateTechnician(anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(false)
                        .setFailureReason("NO_AVAILABLE_TECHNICIAN")
                        .build());

        BookingResponse created = service.createBooking(
                "customer-sub-2",
                new CreateBookingRequest(
                        serviceId,
                        null,
                        "AC",
                        Instant.now().plus(4, ChronoUnit.HOURS),
                        null,
                        "45 Nguyễn Huệ, Q1",
                        null));

        assertThat(created.status()).isEqualTo("REQUESTED");
        assertThat(created.allocationStatus()).isEqualTo("ALLOCATION_FAILED");

        BookingAllocationSagaEntity saga = sagaRepository.findByBookingId(created.id()).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo("FAILED");
        assertThat(saga.getFailureReason()).isEqualTo("NO_AVAILABLE_TECHNICIAN");
    }
}
