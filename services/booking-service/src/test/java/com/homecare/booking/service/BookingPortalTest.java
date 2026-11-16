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
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.common.web.EtagSupport;
import java.util.Set;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
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
        service.cancelBooking(
                new ActorContext("unassigned-customer", AccessMode.PERSONAL, null,
                        Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of()),
                booking.getId(),
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

    @Test void adminAssignsBookingWithoutProviderAndPersistsAllocatedProvider() throws Exception {
        var now = Instant.now();
        var booking = bookings.saveAndFlush(BookingEntity.builder().id(UUID.randomUUID()).requesterProfileId("no-provider-customer")
                .serviceId(UUID.randomUUID()).serviceName("Test")
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Test address").status("REQUESTED").allocationStatus("ALLOCATION_FAILED")
                .createdAt(now).updatedAt(now).build());
        UUID technician = UUID.randomUUID(), actualProvider = UUID.randomUUID();
        Mockito.when(provider.assignTechnician(ArgumentMatchers.eq(booking.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.eq(technician)))
                .thenReturn(AllocateTechnicianResponse.newBuilder().setAllocated(true)
                        .setTechnicianId(technician.toString()).setTechnicianProfileId("assigned-tech").setTechnicianName("Tech")
                        .setProviderId(actualProvider.toString()).build());
        var auth = jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN")));
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign").with(auth)
                .header("If-Match", EtagSupport.format(booking.getVersion()))
                .contentType("application/json").content("{\"technicianId\":\"" + technician + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerId").value(actualProvider.toString()));
    }

    @Test void orgOperatorAssignsBookingWithoutProviderOnlyWithMembership() throws Exception {
        var now = Instant.now();
        var booking = bookings.saveAndFlush(BookingEntity.builder().id(UUID.randomUUID()).requesterProfileId("no-provider-customer-2")
                .serviceId(UUID.randomUUID()).serviceName("Test")
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Test address").status("REQUESTED").allocationStatus("ALLOCATION_FAILED")
                .createdAt(now).updatedAt(now).build());
        UUID org = UUID.randomUUID(), technician = UUID.randomUUID();
        Mockito.when(provider.verify(org, "org-operator"))
                .thenReturn(VerifiedMembership.of(org, "org-operator", "ORG_OWNER", true));
        Mockito.when(provider.assignTechnician(ArgumentMatchers.eq(booking.getId()), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(org), ArgumentMatchers.eq(technician)))
                .thenReturn(AllocateTechnicianResponse.newBuilder().setAllocated(true)
                        .setTechnicianId(technician.toString()).setTechnicianProfileId("assigned-tech").setTechnicianName("Tech")
                        .setProviderId(org.toString()).build());
        String body = "{\"technicianId\":\"" + technician + "\"}";
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign")
                .with(jwt().jwt(j -> j.subject("outsider").claim("user_roles", List.of("ORGANIZATION"))))
                .header("X-Organization-Id", org).header("If-Match", EtagSupport.format(booking.getVersion()))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/assign")
                .with(jwt().jwt(j -> j.subject("org-operator").claim("user_roles", List.of("ORGANIZATION"))))
                .header("X-Organization-Id", org).header("If-Match", EtagSupport.format(booking.getVersion()))
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.providerId").value(org.toString()));
    }

    @Test void orgOperatorWithoutOrgHeaderUsesPersonalBookingScope() throws Exception {
        String user = "org-personal-" + UUID.randomUUID();
        var own = save(user, "REQUESTED");
        var other = save("other-" + UUID.randomUUID(), "REQUESTED");
        var orgAuth = jwt().jwt(j -> j.subject(user).claim("user_roles", List.of("ORGANIZATION")));

        mvc.perform(get("/api/v1/bookings").with(orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(own.getId().toString()));
        mvc.perform(get("/api/v1/bookings/" + own.getId()).with(orgAuth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + other.getId()).with(orgAuth))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/" + other.getId() + "/cancel").with(orgAuth)
                .header("If-Match", EtagSupport.format(other.getVersion())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + own.getId() + "/cancel").with(orgAuth)
                .header("If-Match", EtagSupport.format(own.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test void orgOperatorListsOrgBookingsWithHeaderAndRejectsCrossOrg() throws Exception {
        var booking = save("cust-org-" + UUID.randomUUID(), "REQUESTED");
        UUID orgId = booking.getProviderId();
        UUID otherOrgId = UUID.randomUUID();
        Mockito.when(provider.verify(orgId, "org-staff"))
                .thenReturn(VerifiedMembership.of(orgId, "org-staff", "ORG_STAFF", true));
        var auth = jwt().jwt(j -> j.subject("org-staff").claim("user_roles", List.of("ORGANIZATION")));

        mvc.perform(get("/api/v1/bookings").with(auth).header("X-Organization-Id", orgId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(booking.getId().toString()));
        mvc.perform(get("/api/v1/bookings/" + booking.getId()).with(auth).header("X-Organization-Id", otherOrgId))
                .andExpect(status().isForbidden());
    }

    @Test void technicianWithoutOrgHeaderSeesPersonalAndAssignedBookingsAndSupportsScopeFilter() throws Exception {
        String techUser = "tech-dual-" + UUID.randomUUID();
        Instant now = Instant.now();
        var personal = save(techUser, "REQUESTED");
        var assigned = bookings.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("customer-" + UUID.randomUUID())
                .assignedTechnicianProfileId(techUser)
                .serviceId(UUID.randomUUID()).serviceName("Assigned Test").providerId(UUID.randomUUID())
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Assigned address").status("ASSIGNED").allocationStatus("ALLOCATED")
                .createdAt(now).updatedAt(now).build());
        save("unrelated-" + UUID.randomUUID(), "REQUESTED");

        var techAuth = jwt().jwt(j -> j.subject(techUser).claim("user_roles", List.of("TECHNICIAN")));
        var customerAuth = jwt().jwt(j -> j.subject("plain-customer-" + UUID.randomUUID()));

        // Default (no scope) returns both personal booking and assigned job
        mvc.perform(get("/api/v1/bookings").with(techAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[*].id", Matchers.containsInAnyOrder(
                        personal.getId().toString(), assigned.getId().toString())));

        // scope=CUSTOMER returns only personal booking
        mvc.perform(get("/api/v1/bookings?scope=CUSTOMER").with(techAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(personal.getId().toString()));

        // scope=TECHNICIAN returns only assigned job
        mvc.perform(get("/api/v1/bookings?scope=TECHNICIAN").with(techAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(assigned.getId().toString()));

        // Non-technician requesting scope=TECHNICIAN is rejected
        mvc.perform(get("/api/v1/bookings?scope=TECHNICIAN").with(customerAuth))
                .andExpect(status().isForbidden());

        // Invalid scope fails validation
        mvc.perform(get("/api/v1/bookings?scope=INVALID").with(techAuth))
                .andExpect(status().isBadRequest());
    }

    @Test void dualRoleOrgAndTechnicianCanPostWorkUpdateWithoutOrgHeader() throws Exception {
        String techUser = "dual-tech-" + UUID.randomUUID();
        var now = Instant.now();
        var booking = bookings.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId("customer-" + UUID.randomUUID())
                .assignedTechnicianProfileId(techUser)
                .serviceId(UUID.randomUUID())
                .serviceName("AC Repair")
                .providerId(UUID.randomUUID())
                .preferredStart(now.plusSeconds(3600))
                .preferredEnd(now.plusSeconds(7200))
                .address("123 Main St")
                .status("ASSIGNED")
                .allocationStatus("ALLOCATED")
                .createdAt(now)
                .updatedAt(now)
                .build());

        var dualAuth = jwt().jwt(j -> j.subject(techUser).claim("user_roles", List.of("ORGANIZATION", "TECHNICIAN")));
        mvc.perform(post("/api/v1/bookings/" + booking.getId() + "/work-updates")
                        .with(dualAuth)
                        .header("If-Match", EtagSupport.format(booking.getVersion()))
                        .contentType("application/json")
                        .content("{\"kind\":\"ARRIVED\",\"note\":\"On site\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test void enforcesTechnicianIsolationAndDualRoleReadContinuityInOrgMode() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String techA = "tech-a-" + UUID.randomUUID();
        String techB = "tech-b-" + UUID.randomUUID();
        var now = Instant.now();

        // Tech A's personal booking placed at Org B
        var personalInOrgB = bookings.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID()).requesterProfileId(techA).providerId(orgB)
                .serviceId(UUID.randomUUID()).serviceName("Personal OrgB")
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Addr B").status("REQUESTED").allocationStatus("ALLOCATION_FAILED")
                .createdAt(now).updatedAt(now).build());
        // Booking assigned to Tech A in Org A
        var assignedToA = bookings.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID()).requesterProfileId("cust-1").providerId(orgA)
                .assignedTechnicianProfileId(techA).serviceId(UUID.randomUUID()).serviceName("Assigned A")
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Addr A1").status("ASSIGNED").allocationStatus("ALLOCATED")
                .createdAt(now).updatedAt(now).build());
        // Booking assigned to Tech B in Org A (must NOT be visible to Tech A)
        var assignedToB = bookings.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID()).requesterProfileId("cust-2").providerId(orgA)
                .assignedTechnicianProfileId(techB).serviceId(UUID.randomUUID()).serviceName("Assigned B")
                .preferredStart(now.plusSeconds(3600)).preferredEnd(now.plusSeconds(7200))
                .address("Addr A2").status("ASSIGNED").allocationStatus("ALLOCATED")
                .createdAt(now).updatedAt(now).build());

        // ORG_TECHNICIAN-only (without global TECHNICIAN role), even with global booking:read
        Mockito.when(provider.verify(orgA, techA))
                .thenReturn(VerifiedMembership.of(orgA, techA, "ORG_TECHNICIAN", true));
        var orgTechJwt = jwt().jwt(j -> j.subject(techA)
                .claim("user_roles", List.of("CUSTOMER"))
                .claim("permissions", List.of("booking:read")));

        // 1. Tech A in OrgA can read own assigned booking & own personal booking in OrgB (#1)
        mvc.perform(get("/api/v1/bookings/" + assignedToA.getId()).with(orgTechJwt).header("X-Organization-Id", orgA))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + personalInOrgB.getId()).with(orgTechJwt).header("X-Organization-Id", orgA))
                .andExpect(status().isOk());

        // 2. Tech A in OrgA CANNOT read Tech B's booking in OrgA (#7 -> 404)
        mvc.perform(get("/api/v1/bookings/" + assignedToB.getId()).with(orgTechJwt).header("X-Organization-Id", orgA))
                .andExpect(status().isNotFound());

        // 3. Tech A in OrgA CANNOT cancel their personal booking in OrgB (403)
        mvc.perform(post("/api/v1/bookings/" + personalInOrgB.getId() + "/cancel")
                        .with(orgTechJwt).header("X-Organization-Id", orgA)
                        .header("If-Match", EtagSupport.format(personalInOrgB.getVersion())))
                .andExpect(status().isForbidden());

        // 4. list?scope=CUSTOMER returns only personalInOrgB
        mvc.perform(get("/api/v1/bookings?scope=CUSTOMER").with(orgTechJwt).header("X-Organization-Id", orgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagination.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(personalInOrgB.getId().toString()));

        // 5. list?scope=TECHNICIAN works for ORG_TECHNICIAN in ORG mode and excludes Tech B's booking
        mvc.perform(get("/api/v1/bookings?scope=TECHNICIAN").with(orgTechJwt).header("X-Organization-Id", orgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagination.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(assignedToA.getId().toString()));

        // 6. Invalid UUID X-Organization-Id returns 400
        mvc.perform(get("/api/v1/bookings").with(orgTechJwt).header("X-Organization-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
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
