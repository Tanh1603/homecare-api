package com.homecare.provider.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "platform_governance_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformGovernanceSettingEntity {

    public static final String KEY_INSPECTION_FEE_SOFT_WARNING_VND = "inspection_fee_soft_warning_vnd";
    public static final String KEY_ORGANIZATION_MAX_RESUBMIT_COUNT = "organization_max_resubmit_count";

    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String settingValue;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
