package com.homecare.commerce.client;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.OrganizationMembershipVerifier;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOrganizationMembershipRequest;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProviderOrgClient implements OrganizationMembershipVerifier {
    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub stub;
    private String workloadToken;

    public ProviderOrgClient() {
    }

    @Autowired
    public ProviderOrgClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    @Override
    public VerifiedMembership verify(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) {
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
        Metadata metadata = new Metadata();
        metadata.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, workloadToken);
        try {
            var result = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyOrganizationMembership(VerifyOrganizationMembershipRequest.newBuilder()
                            .setOrganizationId(organizationId.toString())
                            .setUserId(userId)
                            .build());
            return VerifiedMembership.of(organizationId, userId, result.getRole(), result.getValid());
        } catch (StatusRuntimeException ex) {
            throw new DomainException(CommonErrorCode.MEMBERSHIP_UNAVAILABLE);
        }
    }

    public boolean canOperate(UUID organizationId, String userId) {
        VerifiedMembership membership = verify(organizationId, userId);
        return membership.active() && Set.of(
                RbacConstants.ORG_ROLE_OWNER,
                RbacConstants.ORG_ROLE_ADMIN,
                RbacConstants.ORG_ROLE_STAFF).contains(membership.role());
    }
}
