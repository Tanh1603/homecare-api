package com.homecare.commerce.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CommerceGrpcAuthTest {

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
}
