package com.homecare.household.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOrganizationMembershipRequest;
import com.homecare.provider.v1.VerifyOrganizationMembershipResponse;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class ProviderOrgClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderOrgClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub providerStub;

    @Value("${homecare.internal.workload-token}")
    private String workloadToken;

    public boolean verifyOrganizationMembership(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            Metadata metadata = new Metadata();
            metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
            VerifyOrganizationMembershipResponse response = providerStub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyOrganizationMembership(VerifyOrganizationMembershipRequest.newBuilder()
                            .setOrganizationId(organizationId.toString())
                            .setUserId(userId)
                            .build());
            return response.getValid();
        } catch (Exception e) {
            log.warn("Provider org membership gRPC verification failed for org {} user {}: {}",
                    organizationId, userId, e.getMessage());
            return false;
        }
    }
}
