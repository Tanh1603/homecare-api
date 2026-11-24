package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.grpc.ProviderAllocationGrpcService;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;

import io.grpc.stub.StreamObserver;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class ProviderPortalTest {
    @Autowired
    WebApplicationContext context;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    ServiceOfferingRepository offeringRepository;
    @Autowired
    TechnicianRepository technicianRepository;
    @Autowired
    ScheduleWindowRepository scheduleWindowRepository;
    @Autowired
    ProviderDomainService domainService;
    @Autowired
    ProviderAllocationGrpcService grpcService;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void catalogUsesRealPageSizeAndKeepsTotal() throws Exception {
        mvc.perform(get("/api/v1/services?page=1&size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(Matchers.greaterThan(1)));
    }

    @Test
    void selfProfileResolvesAuthenticatedSubject() throws Exception {
        mvc.perform(get("/api/v1/technicians/me").with(jwt().jwt(j -> j.subject("demo-technician"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileId").value("demo-technician"));
    }

    @Test
    void rejectsForeignOrganizationAndInvalidOffering() throws Exception {
        String body = """
                {"providerId":"%s","name":"Portal offering","category":"AC_CLEANING_MAINTENANCE","basePrice":100,"estimatedDurationMinutes":30}
                """
                .formatted(ProviderDataInitializer.PROVIDER_1_ID);
        mvc.perform(post("/api/v1/services").with(jwt().jwt(j -> j.subject("outsider")))
                .header("X-Organization-Id", ProviderDataInitializer.PROVIDER_1_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/services")
                .with(jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"basePrice\":100", "\"basePrice\":-1")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/services")
                .with(jwt().jwt(j -> j.subject("a0000000-0000-0000-0000-000000000002").claim("user_roles", List.of("ORGANIZATION"))))
                .header("X-Organization-Id", ProviderDataInitializer.PROVIDER_1_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/services")
                .with(jwt().jwt(j -> j.subject("a0000000-0000-0000-0000-000000000002").claim("user_roles", List.of("ORGANIZATION"))))
                .header("X-Organization-Id", ProviderDataInitializer.PROVIDER_2_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanCreateUpdateAndDeactivateOfferingAndTechnician() throws Exception {
        var auth = jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN")));
        var mapper = new ObjectMapper();
        String offering = """
                {"providerId":"%s","name":"MVP offering","category":"AC_CLEANING_MAINTENANCE","basePrice":100,"estimatedDurationMinutes":30}
                """.formatted(ProviderDataInitializer.PROVIDER_1_ID);
        var created = mvc
                .perform(post("/api/v1/services").with(auth).contentType(MediaType.APPLICATION_JSON).content(offering))
                .andExpect(status().isCreated()).andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asString();
        mvc.perform(patch("/api/v1/services/" + id).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"basePrice\":250}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.basePrice").value(250));
        mvc.perform(delete("/api/v1/services/" + id).with(auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/services?q=MVP%20offering")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        String tech = """
                {"providerId":"%s","profileId":"%s","fullName":"MVP technician","specialties":["AC"],"experienceYears":2}
                """
                .formatted(ProviderDataInitializer.PROVIDER_1_ID, UUID.randomUUID());
        created = mvc
                .perform(post("/api/v1/technicians").with(auth).contentType(MediaType.APPLICATION_JSON).content(tech))
                .andExpect(status().isCreated()).andReturn();
        id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asString();
        mvc.perform(patch("/api/v1/technicians/" + id).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"experienceYears\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.experienceYears").value(3));
        mvc.perform(delete("/api/v1/technicians/" + id).with(auth)).andExpect(status().isNoContent());
    }

    @Test
    void userStatusRejectsCustomerAndInvalidStatus() throws Exception {
        String path = "/api/v1/admin/users/" + UUID.randomUUID() + "/status";
        mvc.perform(
                patch(path).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path).with(jwt().jwt(j -> j.claim("user_roles", List.of("ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void technicianCanDeleteOwnFreeWindowWithEtag() throws Exception {
        var auth = jwt().jwt(j -> j.subject("demo-technician").claim("user_roles", List.of("TECHNICIAN")));
        Instant start = Instant.now().plusSeconds(86400L * 60);
        var created = mvc
                .perform(post("/api/v1/technicians/me/schedules").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + start.plusSeconds(3600) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        var tree = new ObjectMapper().readTree(created.getResponse().getContentAsString()).path("data");
        String path = "/api/v1/technicians/me/schedules/" + tree.path("id").asString();
        mvc.perform(delete(path).with(auth)).andExpect(status().is(428));
        mvc.perform(delete(path).with(auth).header("If-Match", created.getResponse().getHeader("ETag")))
                .andExpect(status().isNoContent());
    }

    @Test
    void techniciansMeRequiresAuthAndAdminSchedulesWorkAndMaskBookingId() throws Exception {
        mvc.perform(get("/api/v1/technicians/me")).andExpect(status().isUnauthorized());

        Instant now = Instant.now();
        UUID allocatedBookingId = UUID.randomUUID();
        ScheduleWindow booked = scheduleWindowRepository.save(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(ProviderDataInitializer.DEMO_TECH_ID)
                .startsAt(now.plusSeconds(86400L * 90))
                .endsAt(now.plusSeconds(86400L * 90 + 3600))
                .status("BOOKED")
                .allocatedBookingId(allocatedBookingId)
                .createdAt(now)
                .build());

        try {
            var adminAuth = jwt().jwt(j -> j.subject("admin-1").claim("user_roles", List.of("ADMIN")));
            Instant adminStart = now.plusSeconds(86400L * 95);
            mvc.perform(post("/api/v1/technicians/" + ProviderDataInitializer.DEMO_TECH_ID + "/schedules")
                    .with(adminAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"startsAt\":\"" + adminStart + "\",\"endsAt\":\"" + adminStart.plusSeconds(3600)
                            + "\"}"))
                    .andExpect(status().isCreated());

            // Outsider / customer sees schedule slots with allocatedBookingId masked to
            // null
            mvc.perform(get("/api/v1/technicians/" + ProviderDataInitializer.DEMO_TECH_ID + "/schedules")
                    .with(jwt().jwt(j -> j.subject("other-customer"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[?(@.id=='" + booked.getId() + "')].allocatedBookingId")
                            .value(Matchers.hasItem(Matchers.nullValue())));

            // Admin sees allocatedBookingId
            mvc.perform(get("/api/v1/technicians/" + ProviderDataInitializer.DEMO_TECH_ID + "/schedules")
                    .with(adminAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[?(@.id=='" + booked.getId() + "')].allocatedBookingId")
                            .value(Matchers.hasItem(allocatedBookingId.toString())));
        } finally {
            scheduleWindowRepository.deleteById(booked.getId());
        }
    }

    @Test
    void inactiveProviderHidesOfferingsTechniciansAndDeniesMembershipAndGrpcVerify() throws Exception {
        Instant now = Instant.now();
        UUID inactiveProviderId = UUID.randomUUID();
        String techProfile = "suspended-tech-" + UUID.randomUUID();
        providerRepository.save(ProviderEntity.builder()
                .id(inactiveProviderId)
                .organizationId(inactiveProviderId)
                .ownerProfileId("suspended-owner")
                .name("Suspended Provider Co")
                .city("TP. Hồ Chí Minh")
                .phone("0900000999")
                .ratingAvg(4.0)
                .status("INACTIVE")
                .createdAt(now)
                .build());
        ServiceOffering hiddenOffering = offeringRepository.save(ServiceOffering.builder()
                .id(UUID.randomUUID())
                .providerId(inactiveProviderId)
                .name("HiddenSuspendedService")
                .category("AC")
                .basePrice(100_000L)
                .estimatedDurationMinutes(60)
                .active(true)
                .createdAt(now)
                .build());
        technicianRepository.save(Technician.builder()
                .id(UUID.randomUUID())
                .providerId(inactiveProviderId)
                .profileId(techProfile)
                .fullName("HiddenSuspendedTech")
                .specialties("AC")
                .experienceYears(3)
                .ratingAvg(4.5)
                .activeAllocations(0)
                .status("ACTIVE")
                .createdAt(now)
                .build());

        mvc.perform(get("/api/v1/services?q=HiddenSuspendedService"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/technicians?q=HiddenSuspendedTech"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        assertThat(domainService.resolveMembershipRole(inactiveProviderId, techProfile)).isEmpty();

        final VerifyOfferingResponse[] holder = new VerifyOfferingResponse[1];
        grpcService.verifyOffering(
                VerifyOfferingRequest.newBuilder().setServiceId(hiddenOffering.getId().toString()).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(VerifyOfferingResponse value) {
                        holder[0] = value;
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        assertThat(holder[0]).isNotNull();
        assertThat(holder[0].getActive()).isFalse();
    }

    @Test
    void enforcesTechnicianScheduleIsolationAndCrossOrgProviderDenial() throws Exception {
        Instant start = Instant.now().plusSeconds(86400);
        String scheduleBody = """
                {"startsAt":"%s","endsAt":"%s"}
                """.formatted(start, start.plusSeconds(3600));

        // Another technician in same org cannot create schedule for demo-technician
        mvc.perform(post("/api/v1/technicians/" + ProviderDataInitializer.DEMO_TECH_ID + "/schedules")
                        .with(jwt().jwt(j -> j.subject("other-tech-sub").claim("user_roles", List.of("TECHNICIAN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody))
                .andExpect(status().isForbidden());

        // Org2 header cannot mutate Provider 1
        mvc.perform(patch("/api/v1/providers/" + ProviderDataInitializer.PROVIDER_1_ID)
                        .with(jwt().jwt(j -> j.subject("a0000000-0000-0000-0000-000000000002")
                                .claim("user_roles", List.of("ORGANIZATION"))))
                        .header("X-Organization-Id", ProviderDataInitializer.PROVIDER_2_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijack\"}"))
                .andExpect(status().isForbidden());
    }
}
