package com.homecare.commerce.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.homecare.commerce.entity.PartCompatibilityEntity;
import com.homecare.commerce.entity.PartCompatibilityEntity.CompatibilityStatus;
import com.homecare.commerce.repository.PartCompatibilityRepository;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PartCompatibilityService {

    private final PartCompatibilityRepository repository;

    public CompatibilityResult resolve(UUID partProductId, String deviceType, String deviceBrand, String deviceModel) {
        return repository.findByPartProductId(partProductId).stream()
                .filter(mapping -> matches(mapping, deviceType, deviceBrand, deviceModel))
                .map(mapping -> new CompatibilityResult(
                        mapping.getPartProductId(),
                        mapping.getCompatibilityStatus(),
                        mapping.getSource(),
                        mapping.getVersion()))
                .findFirst()
                .orElse(new CompatibilityResult(partProductId, CompatibilityStatus.UNKNOWN, "UNMAPPED", "1"));
    }

    public CompatibilityResult requireCompatible(
            UUID partProductId, String deviceType, String deviceBrand, String deviceModel) {
        CompatibilityResult result = resolve(partProductId, deviceType, deviceBrand, deviceModel);
        if (result.status() != CompatibilityStatus.VERIFIED_COMPATIBLE) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Part is not verified compatible with the target device.");
        }
        return result;
    }

    public static boolean createsDevice(UUID targetDeviceId, boolean isPartLine) {
        return !isPartLine || targetDeviceId == null;
    }

    private boolean matches(PartCompatibilityEntity mapping, String type, String brand, String model) {
        return equal(mapping.getDeviceType(), type)
                && equal(mapping.getDeviceBrand(), brand)
                && equal(mapping.getDeviceModel(), model);
    }

    private boolean equal(String left, String right) {
        return left != null && right != null
                && left.trim().toLowerCase(Locale.ROOT).equals(right.trim().toLowerCase(Locale.ROOT));
    }

    public record CompatibilityResult(
            UUID partProductId,
            CompatibilityStatus status,
            String source,
            String version) {
    }
}
