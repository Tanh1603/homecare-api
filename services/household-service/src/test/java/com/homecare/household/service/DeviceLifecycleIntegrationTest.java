package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.RegisterSerialRequest;
import com.homecare.household.entity.CareRecord;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.WarrantyRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DeviceLifecycleIntegrationTest {

    @Autowired
    private HouseholdDomainService service;

    @Autowired
    private WarrantyRepository warrantyRepository;

    @Autowired
    private CareRecordRepository careRecordRepository;

    @Test
    void installationReplayIsIdempotentAndPartialQuantityDoesNotDuplicateUnits() {
        String owner = "lifecycle-" + UUID.randomUUID();
        UUID orderLine = UUID.randomUUID();
        var warranties = service.autoActivateFromOrderLineDelivered(
                owner, "Owner", null, "Merchant", UUID.randomUUID(), orderLine, UUID.randomUUID(),
                "AC", "AC", "Brand", "TYPE", "Model", "REQUIRED", "INSTALLATION_COMPLETED",
                12, 1, List.of(), null);
        assertThat(warranties).hasSize(1);
        assertThat(warranties.getFirst().status()).isEqualTo("PENDING_TRIGGER");

        service.autoActivateFromOrderLineDelivered(
                owner, "Owner", null, "Merchant", UUID.randomUUID(), orderLine, UUID.randomUUID(),
                "AC", "AC", "Brand", "TYPE", "Model", "REQUIRED", "INSTALLATION_COMPLETED",
                12, 2, List.of(), null);
        assertThat(warrantyRepository.findAllByOrderLineId(orderLine)).hasSize(2);
    }

    @Test
    void missingSerialStaysPendingUntilSerialRegistrationAndInstallationReplayAddsOneCareRecord() {
        String owner = "lifecycle-" + UUID.randomUUID();
        UUID orderLine = UUID.randomUUID();
        var warranty = service.autoActivateFromOrderLineDelivered(
                owner, "Owner", null, "Merchant", UUID.randomUUID(), orderLine, UUID.randomUUID(),
                "AC", "AC", "Brand", "TYPE", "Model", "REQUIRED", "INSTALLATION_COMPLETED",
                12, 1, List.of(), null).getFirst();
        DeviceResponse device = service.getDevice(owner, warranty.deviceId());

        UUID booking = UUID.randomUUID();
        service.recordInstallationCompleted(booking, device.id(), orderLine, null, null, List.of("evidence-1"));
        assertThat(warrantyRepository.findById(warranty.id()).orElseThrow().getStatus()).isEqualTo("PENDING_SERIAL");
        service.registerSerial(owner, device.id(), new RegisterSerialRequest("SN-" + UUID.randomUUID()));
        assertThat(warrantyRepository.findById(warranty.id()).orElseThrow().getStatus()).isEqualTo("ACTIVE");

        service.recordInstallationCompleted(booking, device.id(), orderLine, null, null, List.of("evidence-1"));
        assertThat(careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(device.id()))
                .filteredOn(CareRecord::getKind, "INSTALLATION")
                .hasSize(1);
    }

    @Test
    void lifecycleIsOwnerScoped() {
        String owner = "lifecycle-" + UUID.randomUUID();
        DeviceResponse device = service.createDevice(owner,
                new CreateDeviceRequest(null, "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        assertThatThrownBy(() -> service.getDeviceLifecycle("other-" + UUID.randomUUID(), device.id()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unknownEligibilityDoesNotCreateDevice() {
        String owner = "lifecycle-" + UUID.randomUUID();
        UUID line = UUID.randomUUID();
        var warranties = service.autoActivateFromOrderLineDelivered(
                owner, "Owner", null, "Merchant", UUID.randomUUID(), line, UUID.randomUUID(),
                "Part", "PART", "Brand", "TYPE", "Model", "UNKNOWN", "DELIVERY_DATE",
                6, 1, List.of(), null);
        assertThat(warranties).hasSize(1);
        assertThat(warranties.getFirst().deviceId()).isNull();
    }

    @Test
    void installationEventRequiresEvidence() {
        assertThatThrownBy(() -> service.recordInstallationCompleted(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, List.of()))
                .isInstanceOf(DomainException.class);
    }
}
