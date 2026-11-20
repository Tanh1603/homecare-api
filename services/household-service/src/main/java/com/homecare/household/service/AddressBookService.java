package com.homecare.household.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.household.dto.AddressDtos.CreateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.LocationResponse;
import com.homecare.household.dto.AddressDtos.UpdateUserAddressRequest;
import com.homecare.household.dto.AddressDtos.UserAddressResponse;
import com.homecare.household.entity.Household;
import com.homecare.household.entity.Location;
import com.homecare.household.entity.LocationLevel;
import com.homecare.household.entity.UserAddress;
import com.homecare.household.exception.HouseholdErrorCode;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.repository.UserAddressRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AddressBookService {

    private final LocationRepository locationRepository;
    private final UserAddressRepository userAddressRepository;
    private final HouseholdRepository householdRepository;

    @Transactional(readOnly = true)
    public List<LocationResponse> listProvinces(String query) {
        return locationRepository.searchActiveProvinces(query).stream()
                .map(p -> toLocationResponse(p, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> listWards(String provinceCode, String query) {
        Location province = locationRepository
                .findByLevelAndOfficialCodeAndActiveTrue(LocationLevel.PROVINCE, provinceCode)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.LOCATION_NOT_FOUND));
        return locationRepository.searchActiveWards(province.getId(), query).stream()
                .map(w -> toLocationResponse(w, province.getOfficialCode()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationResponse getLocationByCode(String officialCode) {
        Location loc = locationRepository.findByOfficialCodeAndActiveTrue(officialCode)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.LOCATION_NOT_FOUND));
        String parentCode = null;
        if (loc.getParentId() != null) {
            parentCode = locationRepository.findById(loc.getParentId())
                    .filter(Location::isActive)
                    .map(Location::getOfficialCode)
                    .orElse(null);
        }
        return toLocationResponse(loc, parentCode);
    }

    @Transactional(readOnly = true)
    public List<UserAddressResponse> listAddresses(String ownerProfileId) {
        return userAddressRepository
                .findActiveWithLocationByOwner(ownerProfileId)
                .stream()
                .map(this::toAddressResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserAddressResponse getAddress(String ownerProfileId, UUID addressId) {
        UserAddress addr = userAddressRepository.findByIdAndOwnerProfileIdAndActiveTrue(addressId, ownerProfileId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.ADDRESS_NOT_FOUND));
        return toAddressResponse(addr);
    }

    @Transactional
    public UserAddressResponse createAddress(String ownerProfileId, CreateUserAddressRequest request) {
        validateCoordinates(request.latitude(), request.longitude());
        Location ward = requireActiveWard(request.locationCode());
        boolean makeDefault = Boolean.TRUE.equals(request.defaultShipping());

        if (makeDefault) {
            List<UserAddress> locked = userAddressRepository.lockAllByOwnerOrderById(ownerProfileId);
            for (UserAddress existing : locked) {
                if (existing.isDefaultShipping()) {
                    existing.setDefaultShipping(false);
                }
            }
            userAddressRepository.flush();
        }

        Instant now = Instant.now();
        UserAddress entity = UserAddress.builder()
                .id(UUID.randomUUID())
                .ownerProfileId(ownerProfileId)
                .label(request.label().trim())
                .recipientName(request.recipientName().trim())
                .phoneNumber(request.phoneNumber().trim())
                .addressLine(request.addressLine().trim())
                .locationId(ward.getId())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .defaultShipping(makeDefault)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        UserAddress saved;
        try {
            saved = userAddressRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (makeDefault) {
                throw new DomainException(HouseholdErrorCode.DEFAULT_ADDRESS_CONFLICT);
            }
            throw e;
        }
        return toAddressResponse(saved);
    }

    @Transactional
    public UserAddressResponse updateAddress(String ownerProfileId, UUID addressId, UpdateUserAddressRequest request) {
        UserAddress addr = userAddressRepository.lockByIdAndOwner(addressId, ownerProfileId)
                .filter(UserAddress::isActive)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.ADDRESS_NOT_FOUND));

        if (request.getLabel() != null && !request.getLabel().isBlank()) {
            addr.setLabel(request.getLabel().trim());
        }
        if (request.getRecipientName() != null && !request.getRecipientName().isBlank()) {
            addr.setRecipientName(request.getRecipientName().trim());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            addr.setPhoneNumber(request.getPhoneNumber().trim());
        }
        if (request.getAddressLine() != null && !request.getAddressLine().isBlank()) {
            addr.setAddressLine(request.getAddressLine().trim());
        }
        if (request.getLocationCode() != null && !request.getLocationCode().isBlank()) {
            Location ward = requireActiveWard(request.getLocationCode());
            addr.setLocationId(ward.getId());
        }
        if (request.isLatitudeProvided() || request.isLongitudeProvided()) {
            Double nextLat = request.isLatitudeProvided() ? request.getLatitude() : addr.getLatitude();
            Double nextLng = request.isLongitudeProvided() ? request.getLongitude() : addr.getLongitude();
            validateCoordinates(nextLat, nextLng);
            addr.setLatitude(nextLat);
            addr.setLongitude(nextLng);
        }
        addr.setUpdatedAt(Instant.now());
        UserAddress saved = userAddressRepository.save(addr);

        UserAddressResponse response = toAddressResponse(saved);
        List<Household> linkedHouseholds = householdRepository
                .findByOwnerProfileIdAndDefaultServiceAddressId(ownerProfileId, addressId);
        for (Household h : linkedHouseholds) {
            h.setAddress(response.formattedAddress());
            h.setUpdatedAt(saved.getUpdatedAt());
            householdRepository.save(h);
        }
        return response;
    }

    @Transactional
    public UserAddressResponse setDefaultAddress(String ownerProfileId, UUID addressId) {
        List<UserAddress> locked = userAddressRepository.lockAllByOwnerOrderById(ownerProfileId);
        UserAddress target = locked.stream()
                .filter(a -> a.getId().equals(addressId) && a.isActive())
                .findFirst()
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.ADDRESS_NOT_FOUND));

        Instant now = Instant.now();
        for (UserAddress a : locked) {
            if (a.isDefaultShipping() && !a.getId().equals(addressId)) {
                a.setDefaultShipping(false);
                a.setUpdatedAt(now);
            }
        }
        userAddressRepository.flush();

        target.setDefaultShipping(true);
        target.setUpdatedAt(now);
        return toAddressResponse(userAddressRepository.save(target));
    }

    @Transactional
    public void deleteAddress(String ownerProfileId, UUID addressId) {
        UserAddress addr = userAddressRepository.lockByIdAndOwner(addressId, ownerProfileId)
                .filter(UserAddress::isActive)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.ADDRESS_NOT_FOUND));

        Instant now = Instant.now();
        addr.setActive(false);
        addr.setDefaultShipping(false);
        addr.setUpdatedAt(now);
        userAddressRepository.save(addr);

        List<Household> linkedHouseholds = householdRepository
                .findByOwnerProfileIdAndDefaultServiceAddressId(ownerProfileId, addressId);
        for (Household h : linkedHouseholds) {
            h.setDefaultServiceAddressId(null);
            h.setUpdatedAt(now);
            householdRepository.save(h);
        }
    }

    public UserAddressResponse toAddressResponse(UserAddress addr) {
        Location ward = addr.getLocation() != null
                ? addr.getLocation()
                : locationRepository.findById(addr.getLocationId())
                        .orElseThrow(() -> new DomainException(HouseholdErrorCode.LOCATION_NOT_FOUND));
        Location province = ward.getParent() != null
                ? ward.getParent()
                : (ward.getParentId() != null
                        ? locationRepository.findById(ward.getParentId())
                                .orElseThrow(() -> new DomainException(HouseholdErrorCode.LOCATION_NOT_FOUND))
                        : null);
        String provinceCode = province != null ? province.getOfficialCode() : "";
        String provinceName = province != null ? province.getName() : "";
        String formatted = formatAddress(addr.getAddressLine(), ward.getName(), provinceName);
        return new UserAddressResponse(
                addr.getId(),
                addr.getLabel(),
                addr.getRecipientName(),
                addr.getPhoneNumber(),
                addr.getAddressLine(),
                ward.getOfficialCode(),
                ward.getName(),
                provinceCode,
                provinceName,
                formatted,
                addr.getLatitude(),
                addr.getLongitude(),
                addr.isDefaultShipping(),
                addr.getCreatedAt(),
                addr.getUpdatedAt());
    }

    private Location requireActiveWard(String locationCode) {
        Location loc = locationRepository.findByLevelAndOfficialCodeAndActiveTrue(LocationLevel.WARD, locationCode.trim())
                .orElseThrow(() -> new DomainException(
                        CommonErrorCode.VALIDATION_FAILED,
                        "locationCode must reference an active WARD."));
        if (loc.getParentId() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "WARD must have a parent PROVINCE.");
        }
        Location parent = locationRepository.findById(loc.getParentId())
                .filter(p -> p.isActive() && p.getLevel() == LocationLevel.PROVINCE)
                .orElseThrow(() -> new DomainException(
                        CommonErrorCode.VALIDATION_FAILED,
                        "WARD parent PROVINCE is missing or inactive."));
        return loc;
    }

    private void validateCoordinates(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new DomainException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "latitude and longitude must both be provided or both be null.");
        }
        if (latitude != null && (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0)) {
            throw new DomainException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "latitude must be in [-90, 90] and longitude in [-180, 180].");
        }
    }

    public static String formatAddress(String addressLine, String wardName, String provinceName) {
        if (provinceName == null || provinceName.isBlank()) {
            return addressLine + ", " + wardName;
        }
        return addressLine + ", " + wardName + ", " + provinceName;
    }

    private LocationResponse toLocationResponse(Location l, String parentCode) {
        return new LocationResponse(
                l.getOfficialCode(),
                l.getName(),
                l.getLevel().name(),
                l.getCodename(),
                l.getDivisionType(),
                l.getPhoneCode(),
                parentCode);
    }
}
