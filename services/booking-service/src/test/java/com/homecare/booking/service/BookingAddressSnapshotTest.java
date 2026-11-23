package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.ResolvedServiceAddress;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;

@SpringBootTest
@ActiveProfiles("test")
class BookingAddressSnapshotTest {

    @Autowired
    private BookingLifecycleService service;

    @Autowired
    private BookingRepository bookingRepository;

    @MockitoBean
    private ProviderGrpcClientAdapter providerGrpcClient;

    @MockitoBean
    private HouseholdVerificationClient householdVerificationClient;

    private UUID stubOfferingAndAllocation() {
        UUID serviceId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), anyString())).thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId)).thenReturn(VerifyOfferingResponse.newBuilder()
                .setActive(true)
                .setServiceId(serviceId.toString())
                .setProviderId(providerId.toString())
                .setName("Sua dieu hoa")
                .setCategory("AC")
                .setBasePrice(300000L)
                .setEstimatedDurationMinutes(60)
                .build());
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(UUID.randomUUID().toString())
                        .setTechnicianProfileId("tech-1")
                        .setTechnicianName("Tho A")
                        .build());
        return serviceId;
    }

    @Test
    void addressIdTakesPrecedenceAndPersistsFullSnapshot() {
        UUID serviceId = stubOfferingAndAllocation();
        UUID addressId = UUID.randomUUID();
        when(householdVerificationClient.resolveUserAddress(addressId, "cust-1"))
                .thenReturn(new ResolvedServiceAddress(
                        addressId,
                        "Nguyen Van A",
                        "0901234567",
                        "10 Hai Ba Trung",
                        "26734",
                        "Phường Tân Định",
                        "79",
                        "Thành phố Hồ Chí Minh",
                        "10 Hai Ba Trung, Phường Tân Định, Thành phố Hồ Chí Minh",
                        10.78,
                        106.69));

        Instant start = Instant.now().plus(3, ChronoUnit.HOURS);
        BookingResponse created = service.createBooking("cust-1", new CreateBookingRequest(
                serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS),
                "Client Fake Address", "note", "STANDARD", null, null, addressId));

        assertThat(created.address()).isEqualTo("10 Hai Ba Trung, Phường Tân Định, Thành phố Hồ Chí Minh");
        assertThat(created.addressSnapshot()).isNotNull();
        assertThat(created.addressSnapshot().wardCode()).isEqualTo("26734");
        assertThat(created.addressSnapshot().provinceCode()).isEqualTo("79");
        assertThat(created.addressSnapshot().latitude()).isEqualTo(10.78);
    }

    @Test
    void rejectsWhenHouseholdUnavailableOrCrossOwnerAndNeverFallsBackToString() {
        UUID serviceId = stubOfferingAndAllocation();
        UUID addressId = UUID.randomUUID();
        long countBefore = bookingRepository.count();

        when(householdVerificationClient.resolveUserAddress(addressId, "cust-1"))
                .thenThrow(new DomainException(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE));

        Instant start = Instant.now().plus(3, ChronoUnit.HOURS);
        assertThatThrownBy(() -> service.createBooking("cust-1", new CreateBookingRequest(
                serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS),
                "Fallback String Should Not Be Used", "note", "STANDARD", null, null, addressId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE.name());

        doThrow(new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Service address not found."))
                .when(householdVerificationClient).resolveUserAddress(addressId, "cust-1");
        assertThatThrownBy(() -> service.createBooking("cust-1", new CreateBookingRequest(
                serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS),
                "Fallback String Should Not Be Used", "note", "STANDARD", null, null, addressId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND.name());

        assertThat(bookingRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void legacyStringRequestStillWorks() {
        UUID serviceId = stubOfferingAndAllocation();
        Instant start = Instant.now().plus(3, ChronoUnit.HOURS);
        BookingResponse created = service.createBooking("cust-1", new CreateBookingRequest(
                serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS),
                "Legacy 123 Le Loi", "note"));

        assertThat(created.address()).isEqualTo("Legacy 123 Le Loi");
        assertThat(created.addressSnapshot()).isNull();
    }
}
