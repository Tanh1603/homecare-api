package com.homecare.booking.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyDeviceReferenceResponse;
import com.homecare.household.v1.VerifyWarrantyRequest;
import com.homecare.household.v1.VerifyWarrantyResponse;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class HouseholdVerificationClient {

    private static final Logger log = LoggerFactory.getLogger(HouseholdVerificationClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY = Metadata.Key.of("X-Internal-Workload-Token",
            Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("householdService")
    private HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub householdStub;

    @Value("${homecare.internal.workload-token}")
    private String workloadToken;

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
        } catch (Exception e) {
            log.warn("Household gRPC verification unreachable ({}), using fallback deviceType", e.getMessage());
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
        } catch (Exception e) {
            log.warn("Household warranty gRPC verification unreachable ({})", e.getMessage());
        }
        return new WarrantyVerificationResult(warrantyId, null, "AC", null, "");
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
