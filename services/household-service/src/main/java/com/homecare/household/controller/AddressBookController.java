package com.homecare.household.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.household.dto.AddressDtos.CreateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.LocationResponse;
import com.homecare.household.dto.AddressDtos.UpdateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UserAddressResponse;
import com.homecare.household.service.AddressBookService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class AddressBookController {

    private final AddressBookService service;
    private final ServletApiResponseFactory responseFactory;

    private String resolveOwner(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId))
                .userId();
    }

    @GetMapping("/locations/provinces")
    public ResponseEntity<ApiResponse<List<LocationResponse>>> listProvinces(
            @RequestParam(required = false) String q,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, service.listProvinces(q), httpRequest));
    }

    @GetMapping("/locations/{provinceCode}/wards")
    public ResponseEntity<ApiResponse<List<LocationResponse>>> listWards(
            @PathVariable String provinceCode,
            @RequestParam(required = false) String q,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listWards(provinceCode, q),
                httpRequest));
    }

    @GetMapping("/locations/{code}")
    public ResponseEntity<ApiResponse<LocationResponse>> getLocation(
            @PathVariable String code,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getLocationByCode(code),
                httpRequest));
    }

    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<UserAddressResponse>>> listAddresses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listAddresses(resolveOwner(jwt, rawOrgId)),
                httpRequest));
    }

    @GetMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<UserAddressResponse>> getAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID addressId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getAddress(resolveOwner(jwt, rawOrgId), addressId),
                httpRequest));
    }

    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<UserAddressResponse>> createAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @Valid @RequestBody CreateUserAddressRequest request,
            HttpServletRequest httpRequest) {
        UserAddressResponse created = service.createAddress(resolveOwner(jwt, rawOrgId), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @PutMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<UserAddressResponse>> updateAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID addressId,
            @Valid @RequestBody UpdateUserAddressRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateAddress(resolveOwner(jwt, rawOrgId), addressId, request),
                httpRequest));
    }

    @PatchMapping("/addresses/{addressId}/default")
    public ResponseEntity<ApiResponse<UserAddressResponse>> setDefaultAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID addressId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.setDefaultAddress(resolveOwner(jwt, rawOrgId), addressId),
                httpRequest));
    }

    @DeleteMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID addressId,
            HttpServletRequest httpRequest) {
        service.deleteAddress(resolveOwner(jwt, rawOrgId), addressId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }
}
