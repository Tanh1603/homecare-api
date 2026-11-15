package com.homecare.booking.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class BookingPortalTest {
    @Autowired WebApplicationContext context;
    @Autowired BookingRepository bookings;
    @Autowired BookingLifecycleService service;
    @Autowired BookingOutboxEventRepository outbox;
    @MockitoBean ProviderGrpcClientAdapter provider;
    @MockitoBean HouseholdVerificationClient household;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void statusFilterAppliesBeforePaginationAndKeepsOwnerScope() throws Exception {
        String user = UUID.randomUUID().toString();
        save(user, "REQUESTED"); save(user, "COMPLETED"); save("another-user", "COMPLETED");
        mvc.perform(get("/api/v1/bookings?status=COMPLETED&size=1").with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(1));
    }
    @Test void customerCannotAssignTechnician() throws Exception {
        var booking = save("customer", "REQUESTED");
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign").with(jwt().jwt(j -> j.subject("customer")))
                .contentType("application/json").content("{\"technicianId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void readOnlyOrganizationMemberCannotDispatch() throws Exception {
        var booking = save("read-only-customer", "REQUESTED");
        Mockito.when(provider.verifyOrganizationMembership(booking.getProviderId(), "reader")).thenReturn(true);
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign")
                .with(jwt().jwt(j -> j.subject("reader").claim("permissions", List.of("booking:read"))))
                .header("X-Organization-Id", booking.getProviderId())
                .header("If-Match", EtagSupport.format(booking.getVersion()))
                .contentType("application/json").content("{\"technicianId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
        Mockito.verify(provider, Mockito.never()).assignTechnician(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test void automaticFallbackUsesActualProviderForPortalScope() {
        UUID serviceId = UUID.randomUUID(), preferred = UUID.randomUUID(), actual = UUID.randomUUID();
        Mockito.when(provider.verifyOffering(serviceId)).thenReturn(VerifyOfferingResponse.newBuilder()
                .setActive(true).setServiceId(serviceId.toString()).setProviderId(preferred.toString())
                .setName("Service").setCategory("AC").setBasePrice(100).setEstimatedDurationMinutes(60).build());
        Mockito.when(provider.allocateTechnician(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder().setAllocated(true)
                        .setTechnicianId(UUID.randomUUID().toString()).setTechnicianProfileId("fallback-tech")
                        .setTechnicianName("Tech").setProviderId(actual.toString()).build());
        var result = service.createBooking("fallback-customer", new CreateBookingRequest(
                serviceId, null, "AC", Instant.now().plusSeconds(3600), null, "Address", null));
        Assertions.assertThat(result.providerId()).isEqualTo(actual);
    }

    @Test void cancellationWithoutAssignmentDoesNotNotifyDemoTechnician() {
        var booking = save("unassigned-customer", "REQUESTED");
        service.cancelBooking("unassigned-customer", false, booking.getId(),
                EtagSupport.format(booking.getVersion()), null);
        Assertions.assertThat(outbox.findAll())
                .filteredOn(e -> e.getAggregateId().equals(booking.getId()))
                .singleElement().satisfies(e -> Assertions.assertThat(e.getPayloadJson()).doesNotContain("demo-technician"));
    }

    @Test void adminAssignsRequestedBookingAndCannotAssignCompletedBooking() throws Exception {
        var booking = save("manual-customer", "REQUESTED");
        UUID technician = UUID.randomUUID();
        Mockito.when(provider.assignTechnician(ArgumentMatchers.eq(booking.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(booking.getProviderId()), ArgumentMatchers.eq(technician)))
                .thenReturn(AllocateTechnicianResponse.newBuilder().setAllocated(true)
                        .setTechnicianId(technician.toString()).setTechnicianProfileId("assigned-tech").setTechnicianName("Tech")
                        .setProviderId(booking.getProviderId().toString()).build());
        var auth = jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN")));
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign").with(auth)
                .header("If-Match", EtagSupport.format(booking.getVersion()))
                .contentType("application/json").content("{\"technicianId\":\"" + technician + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assignedTechnicianId").value(technician.toString()));
        var completed = save("manual-customer", "COMPLETED");
        mvc.perform(post("/api/v1/bookings/" + completed.getId() + "/assign").with(auth)
                .header("If-Match", EtagSupport.format(completed.getVersion()))
                .contentType("application/json").content("{\"technicianId\":\"" + technician + "\"}"))
                .andExpect(status().isConflict());
    }
    private BookingEntity save(String user, String status) {
        var now = Instant.now();
        return bookings.saveAndFlush(BookingEntity.builder().id(UUID.randomUUID()).requesterProfileId(user)
                .serviceId(UUID.randomUUID()).serviceName("Test").providerId(UUID.randomUUID())
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Test address").status(status).allocationStatus("ALLOCATION_FAILED")
                .createdAt(now).updatedAt(now).build());
    }
}
