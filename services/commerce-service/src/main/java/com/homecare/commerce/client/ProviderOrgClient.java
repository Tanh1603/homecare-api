package com.homecare.commerce.client;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import com.homecare.common.error.DomainException;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.*;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderOrgClient {
    @GrpcClient("providerService") private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub stub;
    @Value("${homecare.internal.workload-token}") private String workloadToken;
    public boolean canOperate(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) return false;
        Metadata metadata = new Metadata();
        metadata.put(WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY, workloadToken);
        try {
            var result = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(3, TimeUnit.SECONDS).verifyOrganizationMembership(
                        VerifyOrganizationMembershipRequest.newBuilder().setOrganizationId(organizationId.toString()).setUserId(userId).build());
            return result.getValid() && Set.of("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF").contains(result.getRole());
        } catch (StatusRuntimeException ex) {
            throw new DomainException(SERVICE_UNAVAILABLE,
                    "MEMBERSHIP_UNAVAILABLE", "Membership verification unavailable");
        }
    }
}
