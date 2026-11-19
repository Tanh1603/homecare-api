package com.homecare.household.grpc;

import java.util.UUID;

import com.homecare.household.service.HouseholdDomainService;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyDeviceReferenceResponse;
import com.homecare.household.v1.VerifyWarrantyRequest;
import com.homecare.household.v1.VerifyWarrantyResponse;

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
            StreamObserver<VerifyDeviceReferenceResponse> responseObserver) {
        try {
            UUID deviceId = UUID.fromString(request.getDeviceId());
            var res = domainService.verifyDeviceReference(deviceId, request.getRequesterProfileId());

            VerifyDeviceReferenceResponse response =
                    VerifyDeviceReferenceResponse.newBuilder()
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
            VerifyDeviceReferenceResponse response =
                    VerifyDeviceReferenceResponse.newBuilder()
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
            StreamObserver<VerifyWarrantyResponse> responseObserver) {
        try {
            UUID warrantyId = UUID.fromString(request.getWarrantyId());
            var res = domainService.verifyWarrantyEligibility(warrantyId, request.getRequesterProfileId());

            VerifyWarrantyResponse response =
                    VerifyWarrantyResponse.newBuilder()
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
            VerifyWarrantyResponse response =
                    VerifyWarrantyResponse.newBuilder()
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
