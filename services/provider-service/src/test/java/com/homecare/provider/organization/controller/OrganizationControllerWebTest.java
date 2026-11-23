package com.homecare.provider.organization.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.service.OrganizationDomainService;
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

@SpringBootTest
@ActiveProfiles("test")
class OrganizationControllerWebTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrganizationDomainService organizationService;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void postOrganizationPersistsCapabilityAndLegalEntityType() throws Exception {
        String userId = "web-owner-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Trung Tâm Điện Máy Xanh",
                  "type": "SERVICE",
                  "legalEntityType": "HOUSEHOLD_BUSINESS",
                  "taxCode": "0312345001",
                  "phone": "0901234567",
                  "city": "Hà Nội",
                  "address": "101 Cầu Giấy",
                  "description": "Sửa chữa tại nhà"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("SERVICE"))
                .andExpect(jsonPath("$.data.legalEntityType").value("HOUSEHOLD_BUSINESS"));
    }

    @Test
    void postOrganizationOmittingLegalEntityTypeAllowsLegacyClients() throws Exception {
        String userId = "legacy-owner-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Cửa Hàng Linh Kiện Legacy",
                  "type": "COMMERCE",
                  "phone": "0901234568",
                  "city": "Đà Nẵng",
                  "address": "22 Bạch Đằng"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("COMMERCE"))
                .andExpect(jsonPath("$.data.legalEntityType").value(Matchers.nullValue()));
    }

    @Test
    void postOrganizationRejectsUnknownOrganizationTypeWith400Not500() throws Exception {
        String userId = "bad-type-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Công Ty Gửi Nhầm Type",
                  "type": "COMPANY",
                  "legalEntityType": "COMPANY",
                  "phone": "0901234569",
                  "city": "TP. Hồ Chí Minh"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void postOrganizationRejectsUnknownLegalEntityTypeWith400Not500() throws Exception {
        String userId = "bad-legal-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Công Ty Sai LegalEntityType",
                  "type": "SERVICE",
                  "legalEntityType": "UNKNOWN_LEGAL_FORM",
                  "phone": "0901234570",
                  "city": "TP. Hồ Chí Minh"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void postOrganizationRejectsMissingTypeWith400() throws Exception {
        String userId = "missing-type-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Thiếu Capability Type",
                  "legalEntityType": "COMPANY",
                  "phone": "0901234571",
                  "city": "TP. Hồ Chí Minh"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void crossTenantAccessToAnotherOrganizationIsForbidden() throws Exception {
        String ownerId = "tenant-owner-" + UUID.randomUUID();
        String outsiderId = "tenant-outsider-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Tenant A Org",
                        OrganizationType.BOTH,
                        LegalEntityType.COMPANY,
                        "0311112222",
                        "0909111222",
                        "Hà Nội",
                        "1 Tràng Tiền",
                        "Multi-capability"));
        organizationService.approveOrganization(org.id(), "admin-1");

        mvc.perform(get("/api/v1/organizations/{id}", org.id())
                        .with(jwt().jwt(j -> j.subject(outsiderId))))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/organizations/current")
                        .with(jwt().jwt(j -> j.subject(outsiderId)))
                        .header("X-Organization-Id", org.id().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked Name\"}"))
                .andExpect(status().isForbidden());
    }
}
