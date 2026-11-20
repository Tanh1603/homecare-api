package com.homecare.booking.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.ResolveUserAddressRequest;
import com.homecare.household.v1.ResolveUserAddressResponse;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyDeviceReferenceResponse;
import com.homecare.household.v1.VerifyWarrantyRequest;
import com.homecare.household.v1.VerifyWarrantyResponse;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;
import com.homecare.common.config.HomecareSecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class HouseholdVerificationClient {

    private static final Logger log = LoggerFactory.getLogger(HouseholdVerificationClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY = WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("householdService")
    private HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub householdStub;

    private String workloadToken;

    public HouseholdVerificationClient() {
    }

    @Autowired
    public HouseholdVerificationClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    public String verifyDeviceOrFallback(UUID deviceId, String requesterProfileId, String fallbackDeviceType) {
        if (deviceId == null) {
            return fallbackDeviceType;
        }
        try {
            VerifyDeviceReferenceResponse response = withInternalMetadata(householdStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyDeviceReference(VerifyDeviceReferenceRequest.newBuilder()
                            .setDeviceId(deviceId.toString())
                            .setRequesterProfileId(requesterProfileId)
                            .build());

            if (!response.getValid()) {
                throw new DomainException(BookingErrorCode.DEVICE_REFERENCE_INVALID);
            }
            if (!response.getDeviceType().isBlank()) {
                return response.getDeviceType();
            }
        } catch (DomainException e) {
            throw e;
        } catch (StatusRuntimeException e) {
            log.warn("Household gRPC verification unavailable ({}: {})", e.getStatus().getCode(), e.getMessage());
            throw new DomainException(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("Household gRPC verification unreachable ({})", e.getMessage());
            throw new DomainException(BookingErrorCode.DEVICE_REFERENCE_INVALID);
        }
        return fallbackDeviceType;
    }

    public WarrantyVerificationResult verifyWarranty(UUID warrantyId, String requesterProfileId) {
        if (warrantyId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "warrantyId is required for WARRANTY_CLAIM booking.");
        }
        try {
            VerifyWarrantyResponse response = withInternalMetadata(householdStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyWarranty(VerifyWarrantyRequest.newBuilder()
                            .setWarrantyId(warrantyId.toString())
                            .setRequesterProfileId(requesterProfileId)
                            .build());

            if (response.getExpired()) {
                throw new DomainException(BookingErrorCode.WARRANTY_EXPIRED);
            }
            if (!response.getValid()) {
                throw new DomainException(BookingErrorCode.WARRANTY_INVALID);
            }
            UUID deviceId = parseUuid(response.getDeviceId());
            UUID orgId = parseUuid(response.getOrganizationId());
            String deviceType = !response.getDeviceType().isBlank() ? response.getDeviceType() : "AC";
            String providerName = response.getProviderName();
            return new WarrantyVerificationResult(warrantyId, deviceId, deviceType, orgId, providerName);
        } catch (DomainException e) {
            throw e;
        } catch (StatusRuntimeException e) {
            log.warn("Household warranty gRPC verification unavailable ({}: {})", e.getStatus().getCode(), e.getMessage());
            throw new DomainException(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("Household warranty gRPC verification unreachable ({})", e.getMessage());
            throw new DomainException(BookingErrorCode.WARRANTY_INVALID);
        }
    }

    public ResolvedServiceAddress resolveUserAddress(UUID addressId, String ownerProfileId) {
        if (addressId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "serviceAddressId is required.");
        }
        try {
            ResolveUserAddressResponse response = withInternalMetadata(householdStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .resolveUserAddress(ResolveUserAddressRequest.newBuilder()
                            .setAddressId(addressId.toString())
                            .setOwnerProfileId(ownerProfileId)
                            .build());
            return new ResolvedServiceAddress(
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
                throw new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Service address not found.");
            }
            if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, e.getStatus().getDescription());
            }
            log.warn("Household ResolveUserAddress gRPC unavailable ({}: {})", e.getStatus().getCode(), e.getMessage());
            throw new DomainException(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Household ResolveUserAddress gRPC failed ({})", e.getMessage());
            throw new DomainException(BookingErrorCode.HOUSEHOLD_SERVICE_UNAVAILABLE);
        }
    }

    public record ResolvedServiceAddress(
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

    private HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub withInternalMetadata(
            HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub stub) {
        Metadata metadata = new Metadata();
        metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private UUID parseUuid(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(val.trim());
        } catch (Exception e) {
            return null;
        }
    }

    public record WarrantyVerificationResult(
            UUID warrantyId,
            UUID deviceId,
            String deviceType,
            UUID organizationId,
            String providerName) {
    }
}
