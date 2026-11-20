package com.homecare.household.grpc;

import java.util.UUID;

import com.homecare.common.error.DomainException;
import com.homecare.household.service.AddressBookService;
import com.homecare.household.service.HouseholdDomainService;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc;
import com.homecare.household.v1.ResolveUserAddressRequest;
import com.homecare.household.v1.ResolveUserAddressResponse;
import com.homecare.household.v1.VerifyDeviceReferenceRequest;
import com.homecare.household.v1.VerifyDeviceReferenceResponse;
import com.homecare.household.v1.VerifyWarrantyRequest;
import com.homecare.household.v1.VerifyWarrantyResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class HouseholdVerificationGrpcService extends HouseholdVerificationServiceGrpc.HouseholdVerificationServiceImplBase {

    private final HouseholdDomainService domainService;
    private final AddressBookService addressBookService;

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

    @Override
    public void resolveUserAddress(
            ResolveUserAddressRequest request,
            StreamObserver<ResolveUserAddressResponse> responseObserver) {
        if (request.getOwnerProfileId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("owner_profile_id is required.")
                    .asRuntimeException());
            return;
        }
        UUID addressId;
        try {
            addressId = UUID.fromString(request.getAddressId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid address_id UUID.")
                    .asRuntimeException());
            return;
        }
        try {
            var addr = addressBookService.getAddress(request.getOwnerProfileId().trim(), addressId);
            boolean hasCoords = addr.latitude() != null && addr.longitude() != null;
            ResolveUserAddressResponse.Builder builder = ResolveUserAddressResponse.newBuilder()
                    .setAddressId(addr.id().toString())
                    .setRecipientName(addr.recipientName())
                    .setPhoneNumber(addr.phoneNumber())
                    .setAddressLine(addr.addressLine())
                    .setWardCode(addr.wardCode())
                    .setWardName(addr.wardName())
                    .setProvinceCode(addr.provinceCode())
                    .setProvinceName(addr.provinceName())
                    .setFormattedAddress(addr.formattedAddress())
                    .setHasCoordinates(hasCoords);
            if (hasCoords) {
                builder.setLatitude(addr.latitude()).setLongitude(addr.longitude());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (DomainException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Address not found.")
                    .asRuntimeException());
        }
    }
}
