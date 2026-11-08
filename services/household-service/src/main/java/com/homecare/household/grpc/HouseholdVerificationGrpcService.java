package com.homecare.household.grpc;

import java.util.UUID;

import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.service.HouseholdDomainService;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyWarrantyRequest;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class HouseholdVerificationGrpcService extends HouseholdVerificationServiceGrpc.HouseholdVerificationServiceImplBase {

    private final HouseholdDomainService domainService;

    @Override
    public void verifyDeviceReference(
            VerifyDeviceReferenceRequest request,
            StreamObserver<com.homecare.household.v1.VerifyDeviceReferenceResponse> responseObserver) {
        try {
            UUID deviceId = UUID.fromString(request.getDeviceId());
            VerifyDeviceReferenceResponse res = domainService.verifyDeviceReference(
                    deviceId, request.getRequesterProfileId());

            com.homecare.household.v1.VerifyDeviceReferenceResponse response =
                    com.homecare.household.v1.VerifyDeviceReferenceResponse.newBuilder()
                            .setValid(res.valid())
                            .setDeviceId(res.deviceId() != null ? res.deviceId().toString() : request.getDeviceId())
                            .setHouseholdId(res.householdId() != null ? res.householdId().toString() : "")
                            .setDeviceType(res.deviceType() != null ? res.deviceType() : "")
                            .setDeviceName(res.deviceName() != null ? res.deviceName() : "")
                            .setStatus(res.status() != null ? res.status() : "")
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            com.homecare.household.v1.VerifyDeviceReferenceResponse response =
                    com.homecare.household.v1.VerifyDeviceReferenceResponse.newBuilder()
                            .setValid(false)
                            .setDeviceId(request.getDeviceId())
                            .setStatus("INVALID_OR_NOT_FOUND")
                            .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void verifyWarranty(
            VerifyWarrantyRequest request,
            StreamObserver<com.homecare.household.v1.VerifyWarrantyResponse> responseObserver) {
        try {
            UUID warrantyId = UUID.fromString(request.getWarrantyId());
            VerifyWarrantyResponse res = domainService.verifyWarrantyEligibility(
                    warrantyId, request.getRequesterProfileId());

            com.homecare.household.v1.VerifyWarrantyResponse response =
                    com.homecare.household.v1.VerifyWarrantyResponse.newBuilder()
                            .setValid(res.valid())
                            .setExpired(res.expired())
                            .setWarrantyId(res.warrantyId() != null ? res.warrantyId().toString() : request.getWarrantyId())
                            .setDeviceId(res.deviceId() != null ? res.deviceId().toString() : "")
                            .setDeviceType(res.deviceType() != null ? res.deviceType() : "")
                            .setOrganizationId(res.organizationId() != null ? res.organizationId().toString() : "")
                            .setProviderName(res.providerName() != null ? res.providerName() : "")
                            .setEndsOnIso(res.endsOn() != null ? res.endsOn().toString() : "")
                            .setStatus(res.status() != null ? res.status() : "")
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            com.homecare.household.v1.VerifyWarrantyResponse response =
                    com.homecare.household.v1.VerifyWarrantyResponse.newBuilder()
                            .setValid(false)
                            .setExpired(false)
                            .setWarrantyId(request.getWarrantyId())
                            .setStatus("INVALID_OR_NOT_FOUND")
                            .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
