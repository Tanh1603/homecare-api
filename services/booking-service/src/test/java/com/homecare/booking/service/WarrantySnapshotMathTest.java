package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homecare.booking.entity.BookingPartsUsageEntity;
import com.homecare.common.error.DomainException;

class WarrantySnapshotMathTest {

    @Test
    void computesVnCalendarDayEndsAtWithExactMillisecondBoundary() {
        // 2026-11-22 10:15:30.456+07:00 == 2026-11-22T03:15:30.456Z
        Instant acceptedAt = Instant.parse("2026-11-22T03:15:30.456Z");
        Instant endsAt7 = WarrantySnapshotService.computeEndsAt(acceptedAt, 7);
        Instant endsAt30 = WarrantySnapshotService.computeEndsAt(acceptedAt, 30);

        // Day 0 = 2026-11-22 VN -> +7d = 2026-11-29 23:59:59.999+07:00 = 2026-11-29T16:59:59.999Z
        assertThat(endsAt7).isEqualTo(Instant.parse("2026-11-29T16:59:59.999Z"));
        assertThat(endsAt30).isEqualTo(Instant.parse("2026-12-22T16:59:59.999Z"));

        // Exact boundary 23:59:59.999+07:00 passes; +1ms (00:00:00.000+07:00 next day) fails
        assertThat(WarrantySnapshotService.isWorkmanshipTemporallyEligible(7, endsAt7, Instant.parse("2026-11-29T16:59:59.999Z"))).isTrue();
        assertThat(WarrantySnapshotService.isWorkmanshipTemporallyEligible(7, endsAt7, Instant.parse("2026-11-29T17:00:00.000Z"))).isFalse();
    }

    @Test
    void zeroDayWarrantyProducesNullStartsAtAndEndsAtAndIsIneligible() {
        Instant acceptedAt = Instant.parse("2026-11-22T03:15:30.000Z");
        assertThat(WarrantySnapshotService.computeStartsAt(acceptedAt, 0)).isNull();
        assertThat(WarrantySnapshotService.computeEndsAt(acceptedAt, 0)).isNull();
        assertThat(WarrantySnapshotService.isWorkmanshipTemporallyEligible(0, null, acceptedAt)).isFalse();
    }

    @Test
    void enforcesPlatformHardCapsByCategory() {
        // Repair/Installation: [7, 90]
        WarrantySnapshotService.validateWorkmanshipDuration("AC_REPAIR_INSTALL", 7);
        WarrantySnapshotService.validateWorkmanshipDuration("AC_REPAIR_INSTALL", 90);
        assertThatThrownBy(() -> WarrantySnapshotService.validateWorkmanshipDuration("AC_REPAIR_INSTALL", 6))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> WarrantySnapshotService.validateWorkmanshipDuration("AC_REPAIR_INSTALL", 91))
                .isInstanceOf(DomainException.class);

        // Maintenance/Cleaning: [0, 30]
        WarrantySnapshotService.validateWorkmanshipDuration("AC_CLEANING_MAINTENANCE", 0);
        WarrantySnapshotService.validateWorkmanshipDuration("AC_CLEANING_MAINTENANCE", 30);
        assertThatThrownBy(() -> WarrantySnapshotService.validateWorkmanshipDuration("AC_CLEANING_MAINTENANCE", -1))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> WarrantySnapshotService.validateWorkmanshipDuration("AC_CLEANING_MAINTENANCE", 31))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void detectsMaterialDiscrepancyWhenCommittedPartMissingOrWarrantyDowngraded() {
        List<Map<String, Object>> committed = List.of(
                Map.of("partName", "Compressor Relay", "quantity", 1, "partsWarrantyStatus", "AVAILABLE"));
        BookingPartsUsageEntity downgraded = BookingPartsUsageEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .partName("Compressor Relay")
                .quantity(1)
                .partSource("PROVIDER_SUPPLIER")
                .unitPrice(250_000L)
                .partsWarrantyStatus("NONE")
                .recordedByProfileId("tech-1")
                .createdAt(Instant.now())
                .build();

        var result = WarrantySnapshotService.detectMaterialDiscrepancy(committed, List.of(downgraded));
        assertThat(result.materialDiscrepancy()).isTrue();
        assertThat(result.summary()).contains("downgraded");

        var matching = WarrantySnapshotService.detectMaterialDiscrepancy(
                List.of(Map.of("partName", "Compressor Relay", "quantity", 1)),
                List.of(downgraded));
        assertThat(matching.materialDiscrepancy()).isFalse();
    }
}
