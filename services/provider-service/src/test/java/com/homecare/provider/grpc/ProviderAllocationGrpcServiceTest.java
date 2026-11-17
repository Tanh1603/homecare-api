package com.homecare.provider.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.provider.service.ProviderDataInitializer;
import com.homecare.provider.v1.AllocateTechnicianRequest;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;
import com.homecare.provider.v1.VerifyOrganizationMembershipRequest;
import com.homecare.provider.v1.VerifyOrganizationMembershipResponse;

import io.grpc.stub.StreamObserver;

@SpringBootTest
@ActiveProfiles("test")
class ProviderAllocationGrpcServiceTest {

    @Autowired
    private ProviderAllocationGrpcService grpcService;

    @Autowired
    private WorkloadAuthServerInterceptor workloadAuthServerInterceptor;

    @Test
    void rejectsMissingOrInvalidWorkloadTokenAndAcceptsValidToken() {
        assertThatThrownBy(() -> workloadAuthServerInterceptor.verifyMetadata(new Metadata()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);

        Metadata invalid = new Metadata();
        invalid.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, "wrong-token");
        assertThatThrownBy(() -> workloadAuthServerInterceptor.verifyMetadata(invalid))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);

        Metadata valid = new Metadata();
        valid.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, "test-internal-token");
        workloadAuthServerInterceptor.verifyMetadata(valid);
        assertThat(valid.get(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY)).isEqualTo("test-internal-token");
    }

    @Test
    void verifyOfferingAndAllocateTechnicianRespondWithCompleteFields() {
        final VerifyOfferingResponse[] verifyHolder = new VerifyOfferingResponse[1];
        grpcService.verifyOffering(
                VerifyOfferingRequest.newBuilder()
                        .setServiceId(ProviderDataInitializer.SERVICE_AC_CLEAN_ID.toString())
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(VerifyOfferingResponse value) {
                        verifyHolder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(verifyHolder[0]).isNotNull();
        assertThat(verifyHolder[0].getActive()).isTrue();
        assertThat(verifyHolder[0].getCategory()).isEqualTo("AC");
        assertThat(verifyHolder[0].getBasePrice()).isEqualTo(250_000L);

        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(5, ChronoUnit.HOURS);
        Instant end = start.plus(60, ChronoUnit.MINUTES);
        final AllocateTechnicianResponse[] allocHolder = new AllocateTechnicianResponse[1];

        grpcService.allocateTechnician(
                AllocateTechnicianRequest.newBuilder()
                        .setIntentId("grpc-intent-" + bookingId)
                        .setBookingId(bookingId.toString())
                        .setServiceId(ProviderDataInitializer.SERVICE_AC_CLEAN_ID.toString())
                        .setDeviceType("AC")
                        .setPreferredStartIso(start.toString())
                        .setPreferredEndIso(end.toString())
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(AllocateTechnicianResponse value) {
                        allocHolder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(allocHolder[0]).isNotNull();
        assertThat(allocHolder[0].getAllocated()).isTrue();
        assertThat(allocHolder[0].getTechnicianId()).isEqualTo(ProviderDataInitializer.DEMO_TECH_ID.toString());
        assertThat(allocHolder[0].getTechnicianProfileId()).isEqualTo("demo-technician");

        VerifyOrganizationMembershipResponse okOrgMember = invokeMembership(
                ProviderDataInitializer.PROVIDER_1_ID.toString(), "a0000000-0000-0000-0000-000000000002");
        assertThat(okOrgMember.getValid()).isTrue();

        VerifyOrganizationMembershipResponse okTech = invokeMembership(
                ProviderDataInitializer.PROVIDER_1_ID.toString(), "demo-technician");
        assertThat(okTech.getValid()).isTrue();

        VerifyOrganizationMembershipResponse denied = invokeMembership(
                ProviderDataInitializer.PROVIDER_1_ID.toString(), "stranger-user");
        assertThat(denied.getValid()).isFalse();
    }

    private VerifyOrganizationMembershipResponse invokeMembership(String orgId, String userId) {
        final VerifyOrganizationMembershipResponse[] holder = new VerifyOrganizationMembershipResponse[1];
        grpcService.verifyOrganizationMembership(
                VerifyOrganizationMembershipRequest.newBuilder()
                        .setOrganizationId(orgId)
                        .setUserId(userId)
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(VerifyOrganizationMembershipResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        return holder[0];
    }
}
