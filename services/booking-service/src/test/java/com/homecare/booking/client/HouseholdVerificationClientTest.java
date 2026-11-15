package com.homecare.booking.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

import com.homecare.common.error.DomainException;
import com.homecare.household.v1.HouseholdVerificationServiceGrpc.HouseholdVerificationServiceBlockingStub;

class HouseholdVerificationClientTest {

    @Test
    void failsClosedWhenHouseholdGrpcIsUnreachable() {
        HouseholdVerificationClient client = new HouseholdVerificationClient();
        HouseholdVerificationServiceBlockingStub stub = mock(
                HouseholdVerificationServiceBlockingStub.class, Answers.RETURNS_SELF);
        ReflectionTestUtils.setField(client, "householdStub", stub);
        ReflectionTestUtils.setField(client, "workloadToken", "test-token");

        when(stub.verifyDeviceReference(any())).thenThrow(new RuntimeException("household down"));
        when(stub.verifyWarranty(any())).thenThrow(new RuntimeException("household down"));

        assertThatThrownBy(() -> client.verifyDeviceOrFallback(UUID.randomUUID(), "cust-1", "AC"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("DEVICE_REFERENCE_INVALID");

        assertThatThrownBy(() -> client.verifyWarranty(UUID.randomUUID(), "cust-1"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("WARRANTY_INVALID");
    }
}
