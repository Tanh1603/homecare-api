package com.homecare.grpc.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import com.homecare.provider.v1.AllocateTechnicianRequest;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

class ProviderProtoContractTest {

    @Test
    void verifyOfferingAndAllocateTechnicianProtobufSerialization() {
        VerifyOfferingRequest request = VerifyOfferingRequest.newBuilder()
                .setServiceId("srv_electric_01")
                .build();
        assertThat(request.getServiceId()).isEqualTo("srv_electric_01");

        VerifyOfferingResponse response = VerifyOfferingResponse.newBuilder()
                .setServiceId("srv_electric_01")
                .setName("Sửa chữa điện gia dụng")
                .setBasePrice(250000)
                .setActive(true)
                .build();
        assertThat(response.getActive()).isTrue();
        assertThat(response.getBasePrice()).isEqualTo(250000);

        AllocateTechnicianRequest allocReq = AllocateTechnicianRequest.newBuilder()
                .setIntentId("intent-1234567890123456")
                .setBookingId("bkg-001")
                .setServiceId("srv_electric_01")
                .setPreferredStartIso("2026-11-07T09:00:00Z")
                .setPreferredEndIso("2026-11-07T11:00:00Z")
                .build();
        assertThat(allocReq.getIntentId()).isEqualTo("intent-1234567890123456");

        AllocateTechnicianResponse allocResp = AllocateTechnicianResponse.newBuilder()
                .setIntentId("intent-1234567890123456")
                .setAllocated(true)
                .setTechnicianId("tech-001")
                .setProviderId("prv-001")
                .build();
        assertThat(allocResp.getAllocated()).isTrue();
        assertThat(allocResp.getTechnicianId()).isEqualTo("tech-001");
    }

    @Test
    void workloadAuthServerInterceptorValidatesToken() {
        WorkloadAuthServerInterceptor validator = new WorkloadAuthServerInterceptor("secret-token-123");
        Metadata missingHeaders = new Metadata();
        assertThatThrownBy(() -> validator.verifyMetadata(missingHeaders))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");

        Metadata validHeaders = new Metadata();
        validHeaders.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, "secret-token-123");
        validator.verifyMetadata(validHeaders);
    }
}
