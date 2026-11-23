package com.homecare.provider.service;

import static com.homecare.provider.entity.PlatformGovernanceSettingEntity.KEY_INSPECTION_FEE_SOFT_WARNING_VND;
import static com.homecare.provider.entity.PlatformGovernanceSettingEntity.KEY_ORGANIZATION_MAX_RESUBMIT_COUNT;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.provider.entity.PlatformGovernanceSettingEntity;
import com.homecare.provider.repository.PlatformGovernanceSettingRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlatformGovernanceService {

    public static final long DEFAULT_INSPECTION_FEE_SOFT_WARNING_VND = 500_000L;
    public static final int DEFAULT_ORGANIZATION_MAX_RESUBMIT_COUNT = 3;

    private final PlatformGovernanceSettingRepository settingRepository;

    @Transactional(readOnly = true)
    public long getInspectionFeeSoftWarningVnd() {
        return settingRepository.findById(KEY_INSPECTION_FEE_SOFT_WARNING_VND)
                .map(s -> Long.parseLong(s.getSettingValue()))
                .orElse(DEFAULT_INSPECTION_FEE_SOFT_WARNING_VND);
    }

    @Transactional(readOnly = true)
    public int getOrganizationMaxResubmitCount() {
        return settingRepository.findById(KEY_ORGANIZATION_MAX_RESUBMIT_COUNT)
                .map(s -> Integer.parseInt(s.getSettingValue()))
                .orElse(DEFAULT_ORGANIZATION_MAX_RESUBMIT_COUNT);
    }

    @Transactional(readOnly = true)
    public List<PlatformGovernanceSettingEntity> listAllSettings() {
        return settingRepository.findAll();
    }

    @Transactional
    public PlatformGovernanceSettingEntity updateSetting(String key, String rawValue, String adminUserId) {
        if (key == null || rawValue == null || rawValue.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Setting key and value are required.");
        }
        String normalizedValue = rawValue.trim();
        switch (key) {
            case KEY_INSPECTION_FEE_SOFT_WARNING_VND -> {
                long threshold = parseNonNegativeLong(normalizedValue, "inspection_fee_soft_warning_vnd");
                normalizedValue = Long.toString(threshold);
            }
            case KEY_ORGANIZATION_MAX_RESUBMIT_COUNT -> {
                int maxCount = parseNonNegativeInt(normalizedValue, "organization_max_resubmit_count");
                normalizedValue = Integer.toString(maxCount);
            }
            default -> throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unsupported governance setting key: " + key);
        }

        PlatformGovernanceSettingEntity entity = settingRepository.findById(key)
                .orElse(PlatformGovernanceSettingEntity.builder().settingKey(key).build());
        entity.setSettingValue(normalizedValue);
        entity.setUpdatedBy(adminUserId);
        entity.setUpdatedAt(Instant.now());
        return settingRepository.save(entity);
    }

    private long parseNonNegativeLong(String raw, String field) {
        try {
            long val = Long.parseLong(raw);
            if (val < 0) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, field + " must be >= 0.");
            }
            return val;
        } catch (NumberFormatException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, field + " must be a valid integer.");
        }
    }

    private int parseNonNegativeInt(String raw, String field) {
        try {
            int val = Integer.parseInt(raw);
            if (val < 0) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, field + " must be >= 0.");
            }
            return val;
        } catch (NumberFormatException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, field + " must be a valid integer.");
        }
    }
}
