package com.homecare.household.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.OrganizationMembershipVerifier;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOrganizationMembershipRequest;
import com.homecare.provider.v1.VerifyOrganizationMembershipResponse;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

import com.homecare.common.config.HomecareSecurityProperties;

@Component
public class ProviderOrgClient implements OrganizationMembershipVerifier {

    private static final Logger log = LoggerFactory.getLogger(ProviderOrgClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub providerStub;

    private final String workloadToken;

    public ProviderOrgClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    @Override
    public VerifiedMembership verify(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) {
            return VerifiedMembership.of(organizationId, userId, null, false);
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
            return VerifiedMembership.of(organizationId, userId, response.getRole(), response.getValid());
        } catch (StatusRuntimeException e) {
            log.warn("Provider org membership gRPC unavailable for org {} user {}: status={} desc={}",
                    organizationId, userId, e.getStatus().getCode(), e.getStatus().getDescription());
            throw new DomainException(CommonErrorCode.MEMBERSHIP_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("Provider org membership gRPC verification failed for org {} user {}: {}",
                    organizationId, userId, e.getMessage());
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
    }

    public boolean verifyOrganizationMembership(UUID organizationId, String userId) {
        return verify(organizationId, userId).active();
    }
}
