package com.homecare.household.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.service.HouseholdDomainService;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyWarrantyRequest;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class HouseholdVerificationGrpcServiceTest {

    private HouseholdDomainService domainService;
    private HouseholdVerificationGrpcService grpcService;
    private WorkloadAuthServerInterceptor serverInterceptor;
    private static final String EXPECTED_WORKLOAD_TOKEN = "test-internal-workload-token";

    @BeforeEach
    void setUp() {
        domainService = mock(HouseholdDomainService.class);
        grpcService = new HouseholdVerificationGrpcService(domainService);
        serverInterceptor = new WorkloadAuthServerInterceptor(EXPECTED_WORKLOAD_TOKEN);
    }

    @Test
    void verifyDeviceReference_whenValid_returnsCorrectResponse() {
        UUID deviceId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        String requester = "user-profile-123";

        when(domainService.verifyDeviceReference(eq(deviceId), eq(requester)))
                .thenReturn(new VerifyDeviceReferenceResponse(
                        true, deviceId, householdId, "AIR_CONDITIONER", "Panasonic Inverter", "ACTIVE"));

        final com.homecare.household.v1.VerifyDeviceReferenceResponse[] holder = new com.homecare.household.v1.VerifyDeviceReferenceResponse[1];

        grpcService.verifyDeviceReference(
                VerifyDeviceReferenceRequest.newBuilder()
                        .setDeviceId(deviceId.toString())
                        .setRequesterProfileId(requester)
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(com.homecare.household.v1.VerifyDeviceReferenceResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].getValid()).isTrue();
        assertThat(holder[0].getDeviceId()).isEqualTo(deviceId.toString());
        assertThat(holder[0].getHouseholdId()).isEqualTo(householdId.toString());
        assertThat(holder[0].getDeviceType()).isEqualTo("AIR_CONDITIONER");
        assertThat(holder[0].getDeviceName()).isEqualTo("Panasonic Inverter");
        assertThat(holder[0].getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void verifyDeviceReference_whenException_returnsInvalidStatus() {
        final com.homecare.household.v1.VerifyDeviceReferenceResponse[] holder = new com.homecare.household.v1.VerifyDeviceReferenceResponse[1];

        grpcService.verifyDeviceReference(
                VerifyDeviceReferenceRequest.newBuilder()
                        .setDeviceId("not-a-valid-uuid")
                        .setRequesterProfileId("user-123")
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(com.homecare.household.v1.VerifyDeviceReferenceResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].getValid()).isFalse();
        assertThat(holder[0].getStatus()).isEqualTo("INVALID_OR_NOT_FOUND");
    }

    @Test
    void verifyWarranty_whenValid_returnsCorrectResponse() {
        UUID warrantyId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String requester = "user-profile-123";
        LocalDate endsOn = LocalDate.now().plusMonths(6);

        when(domainService.verifyWarrantyEligibility(eq(warrantyId), eq(requester)))
                .thenReturn(new VerifyWarrantyResponse(
                        true, false, warrantyId, deviceId, "WASHING_MACHINE", orgId, "LG Service", endsOn, "ACTIVE"));

        final com.homecare.household.v1.VerifyWarrantyResponse[] holder = new com.homecare.household.v1.VerifyWarrantyResponse[1];

        grpcService.verifyWarranty(
                VerifyWarrantyRequest.newBuilder()
                        .setWarrantyId(warrantyId.toString())
                        .setRequesterProfileId(requester)
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(com.homecare.household.v1.VerifyWarrantyResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].getValid()).isTrue();
        assertThat(holder[0].getExpired()).isFalse();
        assertThat(holder[0].getWarrantyId()).isEqualTo(warrantyId.toString());
        assertThat(holder[0].getDeviceId()).isEqualTo(deviceId.toString());
        assertThat(holder[0].getDeviceType()).isEqualTo("WASHING_MACHINE");
        assertThat(holder[0].getOrganizationId()).isEqualTo(orgId.toString());
        assertThat(holder[0].getProviderName()).isEqualTo("LG Service");
        assertThat(holder[0].getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void verifyWarranty_whenException_returnsInvalidStatus() {
        final com.homecare.household.v1.VerifyWarrantyResponse[] holder = new com.homecare.household.v1.VerifyWarrantyResponse[1];

        grpcService.verifyWarranty(
                VerifyWarrantyRequest.newBuilder()
                        .setWarrantyId("not-a-valid-uuid")
                        .setRequesterProfileId("user-123")
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(com.homecare.household.v1.VerifyWarrantyResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].getValid()).isFalse();
        assertThat(holder[0].getStatus()).isEqualTo("INVALID_OR_NOT_FOUND");
    }

    @Test
    void workloadAuthServerInterceptor_validatesHeader() {
        Metadata missingHeaders = new Metadata();
        assertThatThrownBy(() -> serverInterceptor.verifyMetadata(missingHeaders))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");

        Metadata wrongTokenHeaders = new Metadata();
        wrongTokenHeaders.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, "wrong-token");
        assertThatThrownBy(() -> serverInterceptor.verifyMetadata(wrongTokenHeaders))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");

        Metadata validHeaders = new Metadata();
        validHeaders.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, EXPECTED_WORKLOAD_TOKEN);
        serverInterceptor.verifyMetadata(validHeaders);
    }
}
