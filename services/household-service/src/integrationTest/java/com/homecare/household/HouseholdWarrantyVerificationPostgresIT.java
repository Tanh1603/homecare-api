package com.homecare.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.household.client.BookingUsageGrpcClient;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.AdminVerifyWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.AttachWarrantyEvidenceRequest;
import com.homecare.household.entity.ManufacturerRegistrationStatus;
import com.homecare.household.entity.Warranty;
import com.homecare.household.repository.WarrantyRepository;
import com.homecare.household.service.HouseholdDomainService;

/**
 * Verifies 006-warranty-and-claims D-14 Admin-only verification on real PostgreSQL 16 with Flyway validate.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.locations.auto-seed-if-empty=false"
})
class HouseholdWarrantyVerificationPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WarrantyRepository warrantyRepository;
    @Autowired HouseholdDomainService domainService;

    @MockitoBean ProviderOrgClient providerOrgClient;
    @MockitoBean BookingUsageGrpcClient bookingUsageGrpcClient;

    @Test
    void enforcesAdminOnlyVerificationWhileAllowingProviderEvidenceSubmission() {
        UUID orgId = UUID.randomUUID();
        String ownerId = "cust-" + UUID.randomUUID();
        String orgAdminId = "org-" + UUID.randomUUID();
        String globalAdminId = "admin-" + UUID.randomUUID();

        ActorContext orgAdmin = new ActorContext(orgAdminId, AccessMode.ORGANIZATION, orgId, Set.of(), Set.of(), Set.of("ORG_ADMIN"), Set.of());
        ActorContext admin = new ActorContext(globalAdminId, AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_ADMIN), Set.of(), Set.of(), Set.of());

        Warranty w = warrantyRepository.saveAndFlush(Warranty.builder()
                .id(UUID.randomUUID())
                .ownerProfileId(ownerId)
                .organizationId(orgId)
                .source("MANUAL")
                .providerName("Daikin VN")
                .startsOn(LocalDate.now())
                .endsOn(LocalDate.now().plusYears(1))
                .status("ACTIVE")
                .manufacturerRegistrationStatus(ManufacturerRegistrationStatus.EXTERNAL_REQUIRED)
                .warrantyCategory("PRODUCT_MANUFACTURER")
                .warrantyIssuer("MANUFACTURER")
                .verificationStatus("UNVERIFIED")
                .validityStatus("ACTIVE")
                .createdAt(Instant.now())
                .build());

        // Provider attaches authorization evidence -> verificationStatus remains UNVERIFIED (D-14)
        var afterEvidence = domainService.attachWarrantyEvidence(
                orgAdmin, w.getId(), new AttachWarrantyEvidenceRequest("https://evidence/auth-cert.pdf"));
        assertThat(afterEvidence.authorizationEvidenceRef()).isEqualTo("https://evidence/auth-cert.pdf");
        assertThat(afterEvidence.verificationStatus()).isEqualTo("UNVERIFIED");

        // Provider attempting to set VERIFIED receives 403 RESOURCE_FORBIDDEN (D-14)
        assertThatThrownBy(() -> domainService.adminVerifyWarranty(
                orgAdmin, w.getId(), "\"v" + afterEvidence.version() + "\"",
                new AdminVerifyWarrantyRequest("VERIFIED", null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_FORBIDDEN.name());

        // Global Admin verifies -> VERIFIED + verifiedBy + verifiedAt persisted
        var verified = domainService.adminVerifyWarranty(
                admin, w.getId(), "\"v" + afterEvidence.version() + "\"",
                new AdminVerifyWarrantyRequest("VERIFIED", "Manufacturer certificate validated"));
        assertThat(verified.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(verified.verifiedBy()).isEqualTo(globalAdminId);
        assertThat(verified.verifiedAt()).isNotNull();
    }
}
