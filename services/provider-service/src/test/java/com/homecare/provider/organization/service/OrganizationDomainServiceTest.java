package com.homecare.provider.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.provider.dto.ProviderDtos.TechnicianInput;
import com.homecare.provider.entity.PlatformGovernanceSettingEntity;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.ResubmitOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.UpdateOrganizationRequest;
import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrganizationAppealEntity;
import com.homecare.provider.organization.entity.OrganizationEnforcementEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationEnforcementRepository;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.organization.security.OrgContext;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.service.PlatformGovernanceService;
import com.homecare.provider.service.ProviderDomainService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class OrganizationDomainServiceTest {

    @Autowired
    private OrganizationDomainService organizationService;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderOutboxEventRepository outboxRepository;

    @Autowired
    private OrganizationMemberRepository memberRepository;

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private ProviderDomainService providerDomainService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformGovernanceService governanceService;

    @Autowired
    private OrganizationEnforcementRepository enforcementRepository;

    @Test
    void lifecyclePendingApproveActivatesProviderAndSuspendWithoutDeleting() {
        String ownerId = "supabase-user-" + UUID.randomUUID();
        OrganizationResponse pending = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Điện Lạnh An Tâm",
                        OrganizationType.SERVICE,
                        LegalEntityType.COMPANY,
                        "0300112233",
                        "0901234999",
                        "Đà Nẵng",
                        "12 Lê Duẩn",
                        "Sửa chữa điện lạnh",
                        "[\"dkkd.pdf\"]"));
        assertThat(pending.status()).isEqualTo(OrganizationStatus.PENDING);

        List<MyOrganizationMembershipResponse> myOrgs = organizationService.listMyOrganizations(ownerId);
        assertThat(myOrgs).hasSize(1);
        assertThat(myOrgs.getFirst().role()).isEqualTo(OrgRole.ORG_OWNER);

        OrganizationResponse approved = organizationService.approveOrganization(pending.id(), "admin-1");
        assertThat(approved.status()).isEqualTo(OrganizationStatus.APPROVED);

        ProviderEntity createdProvider = providerRepository.findByOrganizationId(pending.id()).orElseThrow();
        assertThat(createdProvider.getStatus()).isEqualTo(EntityStatus.ACTIVE.name());

        List<ProviderOutboxEventEntity> events = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(pending.id()))
                .toList();
        assertThat(events).anyMatch(e -> OrganizationDomainService.EVT_APPROVED.equals(e.getEventType()));

        OrganizationResponse suspended = organizationService.suspendOrganization(pending.id());
        assertThat(suspended.status()).isEqualTo(OrganizationStatus.SUSPENDED);
        ProviderEntity afterSuspend = providerRepository.findByOrganizationId(pending.id()).orElseThrow();
        assertThat(afterSuspend.getStatus()).isEqualTo(EntityStatus.INACTIVE.name());
        assertThatThrownBy(() -> organizationService.approveOrganization(pending.id(), "admin-1"))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.INVALID_ORGANIZATION_STATE.name());
        organizationService.reactivateOrganization(pending.id());
        assertThat(providerRepository.findByOrganizationId(pending.id()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectSavesReasonAndPreservesPendingOnlyGuard() {
        String ownerId = "supabase-user-" + UUID.randomUUID();
        OrganizationResponse pending = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Cửa Hàng Chưa Đủ Điều Kiện",
                        OrganizationType.SERVICE,
                        LegalEntityType.INDIVIDUAL,
                        null,
                        "0908887776",
                        "Cần Thơ",
                        null,
                        null,
                        "[\"cccd.pdf\"]"));

        OrganizationResponse rejected = organizationService.rejectOrganization(
                pending.id(),
                "Thiếu giấy phép kinh doanh hợp lệ");
        assertThat(rejected.status()).isEqualTo(OrganizationStatus.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo("Thiếu giấy phép kinh doanh hợp lệ");
    }

    @Test
    void roleSyncGrantsAndOnlyRevokesWhenZeroApprovedMembershipsRemain() {
        String multiOrgUser = "supabase-multi-" + UUID.randomUUID();
        OrganizationResponse orgA = organizationService.registerOrganization(
                multiOrgUser,
                new RegisterOrganizationRequest("Org A", OrganizationType.SERVICE, LegalEntityType.COMPANY, "0312345601", "0901234501", "HCM", null, null, "[\"dkkd-a.pdf\"]"));
        OrganizationResponse orgB = organizationService.registerOrganization(
                "other-owner-" + UUID.randomUUID(),
                new RegisterOrganizationRequest("Org B", OrganizationType.SERVICE, LegalEntityType.COMPANY, "0312345602", "0901234502", "HN", null, null, "[\"dkkd-b.pdf\"]"));

        organizationService.approveOrganization(orgA.id(), "admin-1");
        organizationService.approveOrganization(orgB.id(), "admin-1");
        organizationService.addMember(orgB.id(), new AddMemberRequest(multiOrgUser, OrgRole.ORG_STAFF));

        SupabaseRoleClient mockRoleClient = mock(SupabaseRoleClient.class);
        SupabaseRoleSyncConsumer syncConsumer = new SupabaseRoleSyncConsumer(
                memberRepository, technicianRepository, mockRoleClient, objectMapper);

        // User has 2 approved memberships -> GRANT
        syncConsumer.syncUserGlobalOrganizationRole(multiOrgUser, "test");
        verify(mockRoleClient, times(1)).grantOrganizationRole(multiOrgUser);

        // Suspend Org B -> user still has Org A APPROVED -> must still GRANT (not revoke)
        organizationService.suspendOrganization(orgB.id());
        syncConsumer.syncUserGlobalOrganizationRole(multiOrgUser, "test");
        verify(mockRoleClient, times(2)).grantOrganizationRole(multiOrgUser);

        // Suspend Org A -> user now has 0 approved memberships -> REVOKE
        organizationService.suspendOrganization(orgA.id());
        syncConsumer.syncUserGlobalOrganizationRole(multiOrgUser, "test");
        verify(mockRoleClient, times(1)).revokeOrganizationRole(multiOrgUser);
    }

    @Test
    void technicianCreationAndOrgTechnicianMembershipSyncTechnicianRole() {
        String ownerId = "supabase-owner-tech-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Tech Sync Org",
                        OrganizationType.SERVICE,
                        LegalEntityType.COMPANY,
                        "0312345699",
                        "0901234599",
                        "HCM",
                        null,
                        null,
                        "[\"dkkd-tech.pdf\"]"));
        organizationService.approveOrganization(org.id(), "admin-1");
        ProviderEntity provider = providerRepository.findByOrganizationId(org.id()).orElseThrow();

        SupabaseRoleClient mockRoleClient = mock(SupabaseRoleClient.class);
        SupabaseRoleSyncConsumer syncConsumer = new SupabaseRoleSyncConsumer(
                memberRepository, technicianRepository, mockRoleClient, objectMapper);

        // 1. Creating a Technician via ProviderDomainService grants TECHNICIAN role via outbox
        String techUserId = "supabase-tech-" + UUID.randomUUID();
        var adminActor = new ActorContext(
                ownerId,
                AccessMode.ORGANIZATION,
                org.id(),
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(OrgRole.ORG_OWNER.name()),
                Set.of());
        var createdTech = providerDomainService.saveTechnician(
                null,
                new TechnicianInput(
                        provider.getId(), techUserId, "KTV Test", List.of("AC"), 2, "ACTIVE"),
                adminActor);
        assertThat(outboxRepository.findAll())
                .anyMatch(e -> DomainEventContracts.ORG_MEMBER_CHANGED.equals(e.getEventType())
                        && e.getPayloadJson().contains(techUserId));
        syncConsumer.syncUserGlobalOrganizationRole(techUserId, "technician.created");
        verify(mockRoleClient, times(1)).grantTechnicianRole(techUserId);

        // Deactivating/deleting the Technician revokes TECHNICIAN role
        providerDomainService.deleteTechnician(createdTech.id(), adminActor);
        syncConsumer.syncUserGlobalOrganizationRole(techUserId, "technician.deleted");
        verify(mockRoleClient, times(1)).revokeTechnicianRole(techUserId);

        // 2. Adding ORG_TECHNICIAN member grants TECHNICIAN; demoting to ORG_STAFF revokes it
        String memberTechId = "supabase-member-tech-" + UUID.randomUUID();
        organizationService.addMember(org.id(), new AddMemberRequest(memberTechId, OrgRole.ORG_TECHNICIAN));
        syncConsumer.syncUserGlobalOrganizationRole(memberTechId, "member.added");
        verify(mockRoleClient, times(1)).grantTechnicianRole(memberTechId);

        organizationService.updateMemberRole(org.id(), memberTechId, OrgRole.ORG_STAFF);
        syncConsumer.syncUserGlobalOrganizationRole(memberTechId, "member.role_updated");
        verify(mockRoleClient, times(1)).revokeTechnicianRole(memberTechId);
    }

    @Test
    void cannotDemoteOrRemoveOrganizationOwnerAndCannotApproveRejectedOrg() {
        String ownerId = "supabase-owner-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest("Protected Org", OrganizationType.SERVICE, LegalEntityType.INDIVIDUAL, null, "0901234503", "HCM", null, null, "[\"cccd.pdf\"]"));

        assertThatThrownBy(() -> organizationService.updateMemberRole(org.id(), ownerId, OrgRole.ORG_STAFF))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());

        assertThatThrownBy(() -> organizationService.removeMember(org.id(), ownerId))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());

        organizationService.rejectOrganization(org.id(), "Invalid docs");
        assertThatThrownBy(() -> organizationService.approveOrganization(org.id(), "admin-1"))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.INVALID_ORGANIZATION_STATE.name());
    }

    @Test
    void preventsOrgOwnerAssignmentAndSelfRoleModification() {
        String ownerId = "supabase-owner-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest("Role Guard Org", OrganizationType.SERVICE, LegalEntityType.COMPANY, "0312345604", "0901234504", "HCM", null, null, "[\"dkkd.pdf\"]"));
        organizationService.approveOrganization(org.id(), "admin-1");

        String targetUser = "supabase-admin-" + UUID.randomUUID();
        assertThatThrownBy(() -> organizationService.addMember(
                org.id(), new AddMemberRequest(targetUser, OrgRole.ORG_OWNER)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());

        organizationService.addMember(org.id(), new AddMemberRequest(targetUser, OrgRole.ORG_ADMIN));

        assertThatThrownBy(() -> organizationService.updateMemberRole(org.id(), targetUser, OrgRole.ORG_OWNER))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());

        OrgContext.set(new OrgContext(org.id(), targetUser, OrgRole.ORG_ADMIN));
        try {
            assertThatThrownBy(() -> organizationService.updateMemberRole(org.id(), targetUser, OrgRole.ORG_STAFF))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());
        } finally {
            OrgContext.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerOrganizationPersistsCapabilityAndLegalEntityType() throws Exception {
        String ownerId = "supabase-legal-" + UUID.randomUUID();
        OrganizationResponse created = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Công Ty Cơ Điện Lạnh Minh Khôi",
                        OrganizationType.SERVICE,
                        LegalEntityType.COMPANY,
                        "0319998887",
                        "0903334445",
                        "TP. Hồ Chí Minh",
                        "99 Nguyễn Huệ",
                        "Bảo trì điều hòa",
                        "[\"dkkd.pdf\"]"));

        assertThat(created.type()).isEqualTo(OrganizationType.SERVICE);
        assertThat(created.legalEntityType()).isEqualTo(LegalEntityType.COMPANY);

        List<MyOrganizationMembershipResponse> memberships = organizationService.listMyOrganizations(ownerId);
        assertThat(memberships).hasSize(1);
        assertThat(memberships.getFirst().type()).isEqualTo(OrganizationType.SERVICE);
        assertThat(memberships.getFirst().legalEntityType()).isEqualTo(LegalEntityType.COMPANY);

        organizationService.approveOrganization(created.id(), "admin-1");
        ProviderOutboxEventEntity approvedEvent = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(created.id())
                        && OrganizationDomainService.EVT_APPROVED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> envelope = objectMapper.readValue(approvedEvent.getPayloadJson(), Map.class);
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data.get("type")).isEqualTo("SERVICE");
        assertThat(data.get("legalEntityType")).isEqualTo("COMPANY");
    }

    @Test
    void rejectsMissingLegalEntityTypeEvidencePhoneAndInvalidTaxCodePerEntityType() {
        String ownerId = "supabase-validation-" + UUID.randomUUID();

        // Missing legalEntityType
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Org Missing Legal", OrganizationType.SERVICE, null,
                        "0319998887", "0904445556", "Hải Phòng", "20 Lạch Tray", null, "[\"doc.pdf\"]")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // Missing identityEvidenceRefs
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Org Missing Evidence", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0319998887", "0904445556", "Hải Phòng", "20 Lạch Tray", null, "  ")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // Invalid VN phone
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Org Bad Phone", OrganizationType.SERVICE, LegalEntityType.INDIVIDUAL,
                        null, "12345", "Hải Phòng", "20 Lạch Tray", null, "[\"cccd.pdf\"]")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // COMPANY requires 10-digit taxCode
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Company Bad MST", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "001090012345", "0904445556", "Hải Phòng", "20 Lạch Tray", null, "[\"dkkd.pdf\"]")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // HOUSEHOLD_BUSINESS requires 12-digit personal ID
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Household Bad ID", OrganizationType.SERVICE, LegalEntityType.HOUSEHOLD_BUSINESS,
                        "0319998887", "0904445556", "Hải Phòng", "20 Lạch Tray", null, "[\"hkd.pdf\"]")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // INDIVIDUAL optional taxCode passes when omitted, fails when malformed
        OrganizationResponse ind = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Thợ Cá Nhân", OrganizationType.SERVICE, LegalEntityType.INDIVIDUAL,
                        null, "+84 904 445 556", "Hải Phòng", "20 Lạch Tray", null, "[\"cccd.pdf\"]"));
        assertThat(ind.phone()).isEqualTo("+84904445556");
        assertThat(ind.taxCode()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceCapabilityPreservesTypeInOutboxAndEnforcesTenantIsolation() throws Exception {
        String ownerId = "supabase-service-" + UUID.randomUUID();
        String outsiderId = "supabase-outsider-" + UUID.randomUUID();
        OrganizationResponse created = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Hộ Kinh Doanh Toàn Diện",
                        OrganizationType.SERVICE,
                        LegalEntityType.HOUSEHOLD_BUSINESS,
                        "001090012345",
                        "0905556667",
                        "Đà Nẵng",
                        "55 Trần Phú",
                        "Dịch vụ bảo trì điều hòa",
                        "[\"hkd.pdf\"]"));

        organizationService.approveOrganization(created.id(), "admin-1");
        assertThat(providerRepository.findByOrganizationId(created.id()))
                .isPresent()
                .get()
                .extracting(ProviderEntity::getStatus)
                .isEqualTo(EntityStatus.ACTIVE.name());

        OrganizationResponse updated = organizationService.updateCurrentOrganization(
                created.id(),
                null,
                new UpdateOrganizationRequest("Hộ Kinh Doanh Toàn Diện Plus", null, null, null, null));
        assertThat(updated.type()).isEqualTo(OrganizationType.SERVICE);
        assertThat(updated.legalEntityType()).isEqualTo(LegalEntityType.HOUSEHOLD_BUSINESS);

        ProviderOutboxEventEntity updatedEvent = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(created.id())
                        && OrganizationDomainService.EVT_UPDATED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> envelope = objectMapper.readValue(updatedEvent.getPayloadJson(), Map.class);
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data.get("type")).isEqualTo("SERVICE");
        assertThat(data.get("legalEntityType")).isEqualTo("HOUSEHOLD_BUSINESS");

        assertThatThrownBy(() -> organizationService.getOrganizationDetail(created.id(), outsiderId, false))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED.name());
    }

    @Test
    void rejectsCommerceAndBothRegistrationInV01() {
        String ownerId = "supabase-v01-" + UUID.randomUUID();
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Shop Commerce", OrganizationType.COMMERCE, LegalEntityType.COMPANY,
                        "031999", "0901112223", "HCM", null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Shop Both", OrganizationType.BOTH, LegalEntityType.COMPANY,
                        "031998", "0901112224", "HCM", null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());
    }

    @Test
    void resubmitRespectsAdminConfigurableMaxLimitAndApprovalSetsIdentityVerified() {
        String ownerId = "supabase-resubmit-" + UUID.randomUUID();
        governanceService.updateSetting(
                PlatformGovernanceSettingEntity.KEY_ORGANIZATION_MAX_RESUBMIT_COUNT, "2", "admin-1");
        try {
            OrganizationResponse org = organizationService.registerOrganization(
                    ownerId,
                    new RegisterOrganizationRequest(
                            "Đơn Vị Sửa Chữa A", OrganizationType.SERVICE, LegalEntityType.HOUSEHOLD_BUSINESS,
                            "001090012345", "0903334445", "HCM", "10 Lê Lợi", "Sửa máy lạnh", "[\"doc-0\"]"));
            assertThat(org.identityVerified()).isFalse();
            assertThat(org.resubmitCount()).isZero();

            // Reject & Resubmit #1
            organizationService.rejectOrganization(org.id(), "Thiếu ảnh CCCD");
            OrganizationResponse r1 = organizationService.resubmitOrganization(
                    org.id(), ownerId, null,
                    new ResubmitOrganizationRequest(
                            "Đơn Vị Sửa Chữa A", LegalEntityType.HOUSEHOLD_BUSINESS,
                            "001090012345", "0903334445", "HCM", "10 Lê Lợi", "Sửa máy lạnh", "[\"doc-1\"]"));
            assertThat(r1.status()).isEqualTo(OrganizationStatus.PENDING);
            assertThat(r1.resubmitCount()).isEqualTo(1);

            // Reject & Resubmit #2
            organizationService.rejectOrganization(org.id(), "Ảnh mờ");
            OrganizationResponse r2 = organizationService.resubmitOrganization(
                    org.id(), ownerId, null,
                    new ResubmitOrganizationRequest(
                            "Đơn Vị Sửa Chữa A", LegalEntityType.HOUSEHOLD_BUSINESS,
                            "001090012345", "0903334445", "HCM", "10 Lê Lợi", "Sửa máy lạnh", "[\"doc-2\"]"));
            assertThat(r2.resubmitCount()).isEqualTo(2);

            // Reject #3 -> Resubmit #3 blocked by configured limit (2)
            organizationService.rejectOrganization(org.id(), "Vẫn chưa rõ");
            assertThatThrownBy(() -> organizationService.resubmitOrganization(
                    org.id(), ownerId, null,
                    new ResubmitOrganizationRequest(
                            "Đơn Vị Sửa Chữa A", LegalEntityType.HOUSEHOLD_BUSINESS,
                            "001090012345", "0903334445", "HCM", "10 Lê Lợi", "Sửa máy lạnh", "[\"doc-3\"]")))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ProviderErrorCode.INVALID_ORGANIZATION_STATE.name());
        } finally {
            governanceService.updateSetting(
                    PlatformGovernanceSettingEntity.KEY_ORGANIZATION_MAX_RESUBMIT_COUNT, "3", "admin-1");
        }
    }

    @Test
    void banForbidsPendingEnforcesTwoAdminAppealAndBlocksBannedOwnerFromNewRegistration() {
        String ownerId = "supabase-ban-" + UUID.randomUUID();
        OrganizationResponse pending = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Công Ty Bị Khóa", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0312223334", "0902223334", "HCM", "20 Nguyễn Trãi", "Sửa chữa", "[\"dkkd.pdf\"]"));

        // PENDING -> SUSPENDED and PENDING -> BANNED must be rejected
        assertThatThrownBy(() -> organizationService.suspendOrganization(
                pending.id(), "Vi phạm", null, false, "admin-1"))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.INVALID_ORGANIZATION_STATE.name());
        assertThatThrownBy(() -> organizationService.banOrganization(
                pending.id(), "Vi phạm nặng", null, false, "admin-1"))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.INVALID_ORGANIZATION_STATE.name());

        organizationService.approveOrganization(pending.id(), "admin-1");
        OrganizationResponse banned = organizationService.banOrganization(
                pending.id(), "Gian lận nghiêm trọng", "[\"ev-1\"]", true, "admin-ban-1");
        assertThat(banned.status()).isEqualTo(OrganizationStatus.BANNED);

        // Banned owner cannot register a new org
        assertThatThrownBy(() -> organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Công Ty Mới", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "031222334", "0902223335", "HCM", null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.OPERATION_FORBIDDEN.name());

        // Appeal: same Admin cannot reverse BANNED; different Admin can
        OrganizationEnforcementEntity enf = enforcementRepository
                .findByOrganizationIdOrderByIssuedAtDesc(pending.id()).getFirst();
        OrganizationAppealEntity appeal = organizationService.submitAppeal(
                pending.id(), ownerId, enf.getId(), "Đã khắc phục và cung cấp chứng từ", "[\"appeal-doc\"]");

        assertThatThrownBy(() -> organizationService.resolveAppeal(
                pending.id(), appeal.getId(), true, "Tự gỡ", OrganizationStatus.APPROVED, "admin-ban-1"))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.OPERATION_FORBIDDEN.name());

        OrganizationAppealEntity resolved = organizationService.resolveAppeal(
                pending.id(), appeal.getId(), true, "Admin thứ 2 xác nhận hợp lệ", OrganizationStatus.APPROVED, "admin-ban-2");
        assertThat(resolved.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void individualCapOneToOneTechnicianAndOwnerSuspensionCascade() {
        String indOwner = "supabase-ind-" + UUID.randomUUID();
        OrganizationResponse indOrg = organizationService.registerOrganization(
                indOwner,
                new RegisterOrganizationRequest(
                        "Thợ Độc Lập Tuấn", OrganizationType.SERVICE, LegalEntityType.INDIVIDUAL,
                        null, "0907778889", "HCM", "100 CMT8", "Thợ điện lạnh", "[\"cccd.pdf\"]"));
        organizationService.approveOrganization(indOrg.id(), "admin-1");

        // Upgrade requires valid taxCode & new evidenceRefs
        assertThatThrownBy(() -> organizationService.requestLegalEntityUpgrade(
                indOrg.id(), indOwner, LegalEntityType.HOUSEHOLD_BUSINESS, "031888999", "[\"hkd.pdf\"]", null))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());
        assertThatThrownBy(() -> organizationService.requestLegalEntityUpgrade(
                indOrg.id(), indOwner, LegalEntityType.HOUSEHOLD_BUSINESS, "079090001234", " ", null))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // INDIVIDUAL org capped at 1 member even while upgrade is pending
        organizationService.requestLegalEntityUpgrade(
                indOrg.id(), indOwner, LegalEntityType.HOUSEHOLD_BUSINESS, "079090001234", "[\"hkd-upgrade.pdf\"]", null);
        assertThatThrownBy(() -> organizationService.addMember(
                indOrg.id(), new AddMemberRequest("extra-user", OrgRole.ORG_STAFF)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.ORG_ROLE_FORBIDDEN.name());

        // Profile update enforces Vietnamese phone format
        assertThatThrownBy(() -> organizationService.updateCurrentOrganization(
                indOrg.id(), null, new UpdateOrganizationRequest(null, "12345", null, null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        // 1:1 active ORG_TECHNICIAN across orgs
        String techUser = "tech-1to1-" + UUID.randomUUID();
        OrganizationResponse compA = organizationService.registerOrganization(
                "owner-a-" + UUID.randomUUID(),
                new RegisterOrganizationRequest(
                        "Công Ty A", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0311110001", "0901110001", "HCM", null, null, "[\"dkkd-a.pdf\"]"));
        OrganizationResponse compB = organizationService.registerOrganization(
                "owner-b-" + UUID.randomUUID(),
                new RegisterOrganizationRequest(
                        "Công Ty B", OrganizationType.SERVICE, LegalEntityType.COMPANY,
                        "0312220002", "0902220002", "HCM", null, null, "[\"dkkd-b.pdf\"]"));
        organizationService.addMember(compA.id(), new AddMemberRequest(techUser, OrgRole.ORG_TECHNICIAN));
        assertThatThrownBy(() -> organizationService.addMember(
                compB.id(), new AddMemberRequest(techUser, OrgRole.ORG_TECHNICIAN)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ProviderErrorCode.MEMBER_ALREADY_EXISTS.name());

        // Suspending ORG_OWNER cascades to INDIVIDUAL org
        organizationService.handleOwnerUserSuspended(indOwner, "Vi phạm tài khoản", "admin-1");
        assertThat(organizationService.getOrganizationDetail(indOrg.id(), "admin-1", true).status())
                .isEqualTo(OrganizationStatus.SUSPENDED);
    }

    @Test
    void rejectPreservesHistoryAndEnqueuesOutboxAndApproveUpgradeEmitsUpdated() {
        String ownerId = "reject-hist-" + UUID.randomUUID();
        OrganizationResponse org = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Tổ Chức Test Lịch Sử", OrganizationType.SERVICE, LegalEntityType.INDIVIDUAL,
                        null, "0908887766", "Hà Nội", "12 Lý Thường Kiệt", "Thợ sửa chữa", "[\"cccd.pdf\"]"));

        organizationService.rejectOrganization(org.id(), "Thiếu ảnh mặt sau CCCD", "admin-r1");
        organizationService.resubmitOrganization(
                org.id(), ownerId, null,
                new ResubmitOrganizationRequest(
                        "Tổ Chức Test Lịch Sử", LegalEntityType.INDIVIDUAL,
                        null, "0908887766", "Hà Nội", "12 Lý Thường Kiệt", "Thợ sửa chữa", "[\"cccd-v2.pdf\"]"));
        organizationService.rejectOrganization(org.id(), "Ảnh mờ không đọc được", "admin-r2");

        List<OrganizationEnforcementEntity> history =
                enforcementRepository.findByOrganizationIdOrderByIssuedAtDesc(org.id());
        assertThat(history).hasSize(2);
        assertThat(history).extracting(OrganizationEnforcementEntity::getActionType)
                .containsOnly("REJECTED");
        assertThat(history).extracting(OrganizationEnforcementEntity::getReason)
                .containsExactlyInAnyOrder("Thiếu ảnh mặt sau CCCD", "Ảnh mờ không đọc được");

        List<ProviderOutboxEventEntity> rejectedEvents = outboxRepository.findAll().stream()
                .filter(e -> org.id().equals(e.getAggregateId())
                        && OrganizationDomainService.EVT_REJECTED.equals(e.getEventType()))
                .toList();
        assertThat(rejectedEvents).hasSize(2);

        // Resubmit, approve, request upgrade, approve upgrade -> emits ORG_UPDATED
        organizationService.resubmitOrganization(
                org.id(), ownerId, null,
                new ResubmitOrganizationRequest(
                        "Tổ Chức Test Lịch Sử", LegalEntityType.INDIVIDUAL,
                        null, "0908887766", "Hà Nội", "12 Lý Thường Kiệt", "Thợ sửa chữa", "[\"cccd-v3.pdf\"]"));
        organizationService.approveOrganization(org.id(), "admin-a1");
        organizationService.requestLegalEntityUpgrade(
                org.id(), ownerId, LegalEntityType.COMPANY, "0319998887", "[\"gpkd-new.pdf\"]", null);
        OrganizationResponse upgraded = organizationService.approveLegalEntityUpgrade(org.id(), "admin-a2");
        assertThat(upgraded.legalEntityType()).isEqualTo(LegalEntityType.COMPANY);
        assertThat(upgraded.pendingLegalEntityUpgrade()).isNull();

        boolean hasUpdatedEvent = outboxRepository.findAll().stream()
                .anyMatch(e -> org.id().equals(e.getAggregateId())
                        && OrganizationDomainService.EVT_UPDATED.equals(e.getEventType()));
        assertThat(hasUpdatedEvent).isTrue();
    }
}
