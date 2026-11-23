package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.entity.WorkUpdateEntity;

class ReplacementPartLinkageTest {

    @Test
    void customerSuppliedPartIsNotProviderStock() {
        WorkUpdateEntity update = WorkUpdateEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .technicianProfileId("tech")
                .kind("DIAGNOSED")
                .partSource("CUSTOMER_SUPPLIED")
                .partOrderLineId(UUID.randomUUID())
                .build();

        assertThat(update.getPartSource()).isEqualTo("CUSTOMER_SUPPLIED");
        assertThat(update.getPartOrderLineId()).isNotNull();
    }

    @Test
    void replacementBookingCarriesOrderLineLinkage() {
        UUID orderId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();

        CreateBookingRequest request = new CreateBookingRequest(
                UUID.randomUUID(), UUID.randomUUID(), "AC", null, null,
                "address", "replacement", "STANDARD", null, null, null,
                orderId, orderLineId, "REPLACEMENT");

        assertThat(request.orderId()).isEqualTo(orderId);
        assertThat(request.orderLineId()).isEqualTo(orderLineId);
        assertThat(request.purpose()).isEqualTo("REPLACEMENT");
    }

    @Test
    void bookingResponseExposesReplacementOrderLinkage() {
        assertThat(Arrays.stream(BookingResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("orderId", "orderLineId", "purpose");
    }
}
