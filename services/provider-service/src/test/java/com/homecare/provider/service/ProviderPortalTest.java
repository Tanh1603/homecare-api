package com.homecare.provider.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class ProviderPortalTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void catalogUsesRealPageSizeAndKeepsTotal() throws Exception {
        mvc.perform(get("/api/v1/services?page=1&size=1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.pagination.totalItems").value(Matchers.greaterThan(1)));
    }

    @Test void selfProfileResolvesAuthenticatedSubject() throws Exception {
        mvc.perform(get("/api/v1/technicians/me").with(jwt().jwt(j -> j.subject("demo-technician"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileId").value("demo-technician"));
    }

    @Test void rejectsForeignOrganizationAndInvalidOffering() throws Exception {
        String body = """
            {"providerId":"%s","name":"Portal offering","category":"AC","basePrice":100,"estimatedDurationMinutes":30}
            """.formatted(ProviderDataInitializer.PROVIDER_1_ID);
        mvc.perform(post("/api/v1/services").with(jwt().jwt(j -> j.subject("outsider")))
                .header("X-Organization-Id", ProviderDataInitializer.PROVIDER_1_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/services").with(jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"basePrice\":100", "\"basePrice\":-1")))
            .andExpect(status().isBadRequest());
    }

    @Test void administratorCanCreateUpdateAndDeactivateOfferingAndTechnician() throws Exception {
        var auth = jwt().jwt(j -> j.subject("admin").claim("user_roles", List.of("ADMIN")));
        var mapper = new ObjectMapper();
        String offering = """
            {"providerId":"%s","name":"MVP offering","category":"AC","basePrice":100,"estimatedDurationMinutes":30}
            """.formatted(ProviderDataInitializer.PROVIDER_1_ID);
        var created = mvc.perform(post("/api/v1/services").with(auth).contentType(MediaType.APPLICATION_JSON).content(offering))
                .andExpect(status().isCreated()).andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/services/" + id).with(auth).contentType(MediaType.APPLICATION_JSON).content("{\"basePrice\":250}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.basePrice").value(250));
        mvc.perform(delete("/api/v1/services/" + id).with(auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/services?q=MVP%20offering")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        String tech = """
            {"providerId":"%s","profileId":"%s","fullName":"MVP technician","specialties":["AC"],"experienceYears":2}
            """.formatted(ProviderDataInitializer.PROVIDER_1_ID, UUID.randomUUID());
        created = mvc.perform(post("/api/v1/technicians").with(auth).contentType(MediaType.APPLICATION_JSON).content(tech))
                .andExpect(status().isCreated()).andReturn();
        id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/technicians/" + id).with(auth).contentType(MediaType.APPLICATION_JSON).content("{\"experienceYears\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.experienceYears").value(3));
        mvc.perform(delete("/api/v1/technicians/" + id).with(auth)).andExpect(status().isNoContent());
    }

    @Test void userStatusRejectsCustomerAndInvalidStatus() throws Exception {
        String path = "/api/v1/admin/users/" + UUID.randomUUID() + "/status";
        mvc.perform(patch(path).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path).with(jwt().jwt(j -> j.claim("user_roles", List.of("ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void technicianCanDeleteOwnFreeWindowWithEtag() throws Exception {
        var auth = jwt().jwt(j -> j.subject("demo-technician").claim("user_roles", List.of("TECHNICIAN")));
        Instant start = Instant.now().plusSeconds(86400L * 60);
        var created = mvc.perform(post("/api/v1/technicians/me/schedules").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + start.plusSeconds(3600) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        var tree = new ObjectMapper().readTree(created.getResponse().getContentAsString()).path("data");
        String path = "/api/v1/technicians/me/schedules/" + tree.path("id").asText();
        mvc.perform(delete(path).with(auth)).andExpect(status().is(428));
        mvc.perform(delete(path).with(auth).header("If-Match", created.getResponse().getHeader("ETag"))).andExpect(status().isNoContent());
    }
}
