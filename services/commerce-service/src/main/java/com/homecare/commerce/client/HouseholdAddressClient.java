package com.homecare.commerce.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.homecare.commerce.exception.CommerceErrorCode;
import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.ResolveUserAddressRequest;
import com.homecare.household.v1.ResolveUserAddressResponse;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class HouseholdAddressClient {

    private static final Logger log = LoggerFactory.getLogger(HouseholdAddressClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY = WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("householdService")
    private HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub householdStub;

    private String workloadToken;

    public HouseholdAddressClient() {
    }

    @Autowired
    public HouseholdAddressClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    public ResolvedShippingAddress resolveUserAddress(UUID addressId, String ownerProfileId) {
        if (addressId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "shippingAddressId is required.");
        }
        try {
            ResolveUserAddressResponse response = withInternalMetadata(householdStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                            .setAddressId(addressId.toString())
                            .setOwnerProfileId(ownerProfileId)
                            .build());
            return new ResolvedShippingAddress(
                    UUID.fromString(response.getAddressId()),
                    response.getRecipientName(),
                    response.getPhoneNumber(),
                    response.getAddressLine(),
                    response.getWardCode(),
                    response.getWardName(),
                    response.getProvinceCode(),
                    response.getProvinceName(),
                    response.getFormattedAddress(),
                    response.getHasCoordinates() ? response.getLatitude() : null,
                    response.getHasCoordinates() ? response.getLongitude() : null);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Shipping address not found.");
            }
            if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, e.getStatus().getDescription());
            }
            log.warn("Household ResolveUserAddress gRPC unavailable ({}: {})", e.getStatus().getCode(), e.getMessage());
            throw new DomainException(CommerceErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Household ResolveUserAddress gRPC failed ({})", e.getMessage());
            throw new DomainException(CommerceErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        }
    }

    private HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub withInternalMetadata(
            HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub stub) {
        Metadata metadata = new Metadata();
        metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    public record ResolvedShippingAddress(
            UUID addressId,
            String recipientName,
            String phoneNumber,
            String addressLine,
            String wardCode,
            String wardName,
            String provinceCode,
            String provinceName,
            String formattedAddress,
            Double latitude,
            Double longitude) {
    }
}
