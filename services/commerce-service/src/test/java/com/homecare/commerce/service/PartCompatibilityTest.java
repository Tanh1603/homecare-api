package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homecare.commerce.entity.PartCompatibilityEntity;
import com.homecare.commerce.entity.PartCompatibilityEntity.CompatibilityStatus;
import com.homecare.commerce.repository.PartCompatibilityRepository;
import com.homecare.common.error.DomainException;

class PartCompatibilityTest {

    private final PartCompatibilityRepository repository = mock(PartCompatibilityRepository.class);
    private final PartCompatibilityService service = new PartCompatibilityService(repository);

    @Test
    void returnsVerifiedCompatibleForCuratedPilotMapping() {
        UUID partId = UUID.randomUUID();
        PartCompatibilityEntity mapping = mapping(partId, "AC", "Daikin", "FTKF25");
        when(repository.findByPartProductId(partId)).thenReturn(List.of(mapping));

        assertThat(service.resolve(partId, "AC", "Daikin", "FTKF25").status())
                .isEqualTo(CompatibilityStatus.VERIFIED_COMPATIBLE);
    }

    @Test
    void labelsUnmappedPartAsUnknown() {
        UUID partId = UUID.randomUUID();
        when(repository.findByPartProductId(partId)).thenReturn(List.of());

        assertThat(service.resolve(partId, "AC", "Daikin", "FTKF25").status())
                .isEqualTo(CompatibilityStatus.UNKNOWN);
    }

    @Test
    void rejectsIncompatibleMappingFromBeingLinked() {
        UUID partId = UUID.randomUUID();
        PartCompatibilityEntity mapping = mapping(partId, "AC", "Daikin", "FTKF25");
        mapping.setCompatibilityStatus(CompatibilityStatus.INCOMPATIBLE);
        when(repository.findByPartProductId(partId)).thenReturn(List.of(mapping));

        assertThatThrownBy(() -> service.requireCompatible(partId, "AC", "Daikin", "FTKF25"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void targetDevicePartLineDoesNotCreateDevice() {
        assertThat(PartCompatibilityService.createsDevice(null, false)).isTrue();
        assertThat(PartCompatibilityService.createsDevice(UUID.randomUUID(), true)).isFalse();
    }

    @Test
    void customerSuppliedPartDoesNotDecrementProviderInventory() {
        assertThat(InventoryFulfillmentPolicy.shouldDecrement("CUSTOMER_SUPPLIED")).isFalse();
        assertThat(InventoryFulfillmentPolicy.shouldDecrement("HOMECARE_ORDER")).isTrue();
    }

    private PartCompatibilityEntity mapping(UUID partId, String type, String brand, String model) {
        return PartCompatibilityEntity.builder()
                .id(UUID.randomUUID())
                .partProductId(partId)
                .deviceType(type)
                .deviceBrand(brand)
                .deviceModel(model)
                .compatibilityStatus(CompatibilityStatus.VERIFIED_COMPATIBLE)
                .source("CURATED_PILOT")
                .version("2026-11-20")
                .build();
    }
}
