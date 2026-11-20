package com.homecare.commerce.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.error.DomainException;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class ProviderOfferingClient {

    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub providerStub;

    private final String workloadToken;

    public ProviderOfferingClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    public VerifyOfferingResponse verify(UUID offeringId) {
        try {
            Metadata metadata = new Metadata();
            metadata.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, workloadToken);
            return providerStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyOffering(VerifyOfferingRequest.newBuilder()
                            .setServiceId(offeringId.toString())
                            .build());
        } catch (StatusRuntimeException ex) {
            throw new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Installation offering unavailable.");
        }
    }
}
