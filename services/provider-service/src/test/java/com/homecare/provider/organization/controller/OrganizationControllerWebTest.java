package com.homecare.provider.organization.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
                  "taxCode": "001090012345",
                  "phone": "0901234567",
                  "city": "Hà Nội",
                  "address": "101 Cầu Giấy",
                  "description": "Sửa chữa tại nhà",
                  "identityEvidenceRefs": "[{\\"documentType\\":\\"HOUSEHOLD_BUSINESS\\",\\"fileName\\":\\"hkd.pdf\\",\\"objectKey\\":\\"org/hkd.pdf\\",\\"bucket\\":\\"evidence\\"}]"
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
    void postOrganizationRejectsMissingLegalEntityTypeOrEvidence() throws Exception {
        String userId = "legacy-owner-" + UUID.randomUUID();
        String payload = """
                {
                  "name": "Cửa Hàng Thiếu Pháp Lý",
                  "type": "SERVICE",
                  "phone": "0901234568",
                  "city": "Đà Nẵng",
                  "address": "22 Bạch Đằng"
                }
                """;

        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void postOrganizationEvidencePresignReturnsUploadMetadata() throws Exception {
        String userId = "presign-owner-" + UUID.randomUUID();
        String payload = """
                {
                  "fileName": "business-license.pdf",
                  "contentType": "application/pdf",
                  "documentType": "COMPANY"
                }
                """;

        mvc.perform(post("/api/v1/organizations/evidence/presign")
                        .with(jwt().jwt(j -> j.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.objectKey").isNotEmpty())
                .andExpect(jsonPath("$.data.method").value("PUT"));
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
                        OrganizationType.SERVICE,
                        LegalEntityType.COMPANY,
                        "0311112222",
                        "0909111222",
                        "Hà Nội",
                        "1 Tràng Tiền",
                        "Service capability",
                        "[\"dkkd.pdf\"]"));
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

    @Test
    void getEvidenceSignedUrlAndSubmitQualificationFlow() throws Exception {
        String ownerId = "qual-owner-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Điện Lạnh Qual Pro",
                        OrganizationType.SERVICE,
                        LegalEntityType.COMPANY,
                        "0311113333",
                        "0909111333",
                        "Hà Nội",
                        "2 Tràng Tiền",
                        "Service capability",
                        "[\"dkkd.pdf\"]"));
        organizationService.approveOrganization(org.id(), "admin-1");

        mvc.perform(get("/api/v1/organizations/{id}/evidence/signed-url", org.id())
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .param("objectKey", "organizations/" + ownerId + "/cert.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedUrl").isNotEmpty());

        // Cross-tenant IDOR protection: member of org cannot sign another tenant's objectKey
        mvc.perform(get("/api/v1/organizations/{id}/evidence/signed-url", org.id())
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .param("objectKey", "organizations/other-tenant-owner/private-cccd.pdf"))
                .andExpect(status().isForbidden());

        // URL-encoded path traversal (%2e%2e) must be rejected with 400
        mvc.perform(get("/api/v1/organizations/{id}/evidence/signed-url", org.id())
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .param("objectKey", "organizations/" + ownerId + "/%2e%2e/victim/cccd.pdf"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/providers/current/qualifications/ELECTRICAL_REPAIR")
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .header("X-Organization-Id", org.id().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceRefs\":\"[{\\\"objectKey\\\":\\\"organizations/" + ownerId + "/cert.pdf\\\"}]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("ELECTRICAL_REPAIR"))
                .andExpect(jsonPath("$.data.status").value("UNVERIFIED"));

        mvc.perform(get("/api/v1/providers/current/qualifications")
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .header("X-Organization-Id", org.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("ELECTRICAL_REPAIR"));

        // Delete evidence object rejects cross-tenant key with 403 and deletes own key with 204
        mvc.perform(delete("/api/v1/organizations/{id}/evidence", org.id())
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .param("objectKey", "organizations/other-tenant/secret.pdf"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/organizations/{id}/evidence", org.id())
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .param("objectKey", "organizations/" + ownerId + "/cert.pdf"))
                .andExpect(status().isNoContent());

        // Delete qualification removes record and returns empty list
        mvc.perform(delete("/api/v1/providers/current/qualifications/ELECTRICAL_REPAIR")
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .header("X-Organization-Id", org.id().toString()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/providers/current/qualifications")
                        .with(jwt().jwt(j -> j.subject(ownerId)))
                        .header("X-Organization-Id", org.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Admin suspend requires non-blank reason body (FR-008)
        mvc.perform(post("/api/v1/admin/organizations/{id}/suspend", org.id())
                        .with(jwt().jwt(j -> j.subject("admin-1")
                                        .claim("app_metadata", Map.of("roles", List.of("ADMIN"))))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/admin/organizations/{id}/suspend", org.id())
                        .with(jwt().jwt(j -> j.subject("admin-1")
                                        .claim("app_metadata", Map.of("roles", List.of("ADMIN"))))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Vi phạm quy chuẩn an toàn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void adminListOrganizationsSearchesFiltersAndSortsAcrossPages() throws Exception {
        String prefix = "SrvSearch-" + UUID.randomUUID().toString().substring(0, 6);
        OrganizationResponse org1 = organizationService.registerOrganization(
                "srv-owner-1-" + UUID.randomUUID(),
                new RegisterOrganizationRequest(
                        prefix + " Alpha", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0310000001", "0901000001", "Hà Nội", "1 A", "Desc", "[\"a.pdf\"]"));
        OrganizationResponse org2 = organizationService.registerOrganization(
                "srv-owner-2-" + UUID.randomUUID(),
                new RegisterOrganizationRequest(
                        prefix + " Beta", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0310000002", "0901000002", "Đà Nẵng", "2 B", "Desc", "[\"b.pdf\"]"));
        OrganizationResponse org3 = organizationService.registerOrganization(
                "srv-owner-3-" + UUID.randomUUID(),
                new RegisterOrganizationRequest(
                        prefix + " GammaTarget", OrganizationType.SERVICE, LegalEntityType.HOUSEHOLD_BUSINESS,
                        "079090000003", "0901000003", "TP. Hồ Chí Minh", "3 C", "Desc", "[\"c.pdf\"]"));
        organizationService.approveOrganization(org2.id(), "admin-1");

        // Page size 1 puts org3 outside page 1 when unfiltered, yet server-side q finds it on page 1
        mvc.perform(get("/api/v1/admin/organizations")
                        .with(jwt().jwt(j -> j.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .param("page", "1")
                        .param("size", "1")
                        .param("q", prefix + " GammaTarget")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(org3.id().toString()))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(1))
                .andExpect(jsonPath("$.data.pagination.totalPages").value(1));

        // Sort ascending by name across prefix returns Alpha first (org1), then Beta (org2), then GammaTarget (org3)
        mvc.perform(get("/api/v1/admin/organizations")
                        .with(jwt().jwt(j -> j.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .param("page", "1")
                        .param("size", "2")
                        .param("q", prefix)
                        .param("sortBy", "name")
                        .param("sortDir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(org1.id().toString()))
                .andExpect(jsonPath("$.data.items[1].id").value(org2.id().toString()))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(3))
                .andExpect(jsonPath("$.data.pagination.totalPages").value(2));
    }
}
