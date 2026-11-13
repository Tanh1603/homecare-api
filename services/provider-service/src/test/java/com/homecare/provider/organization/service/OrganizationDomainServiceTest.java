package com.homecare.provider.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ProviderRepository;

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
    private ObjectMapper objectMapper;

    @Test
    void lifecyclePendingApproveCreateInactiveProviderAndSuspendWithoutDeleting() {
        String ownerId = "supabase-user-" + UUID.randomUUID();
        OrganizationResponse pending = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Điện Lạnh An Tâm",
                        OrganizationType.SERVICE,
                        "0300112233",
                        "0901234999",
                        "Đà Nẵng",
                        "12 Lê Duẩn",
                        "Sửa chữa điện lạnh"));
        assertThat(pending.status()).isEqualTo(OrganizationStatus.PENDING);

        List<MyOrganizationMembershipResponse> myOrgs = organizationService.listMyOrganizations(ownerId);
        assertThat(myOrgs).hasSize(1);
        assertThat(myOrgs.getFirst().role()).isEqualTo(OrgRole.ORG_OWNER);

        OrganizationResponse approved = organizationService.approveOrganization(pending.id(), "admin-1");
        assertThat(approved.status()).isEqualTo(OrganizationStatus.APPROVED);

        ProviderEntity createdProvider = providerRepository.findByOrganizationId(pending.id()).orElseThrow();
        assertThat(createdProvider.getStatus()).isEqualTo("INACTIVE");

        List<ProviderOutboxEventEntity> events = outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(pending.id()))
                .toList();
        assertThat(events).anyMatch(e -> OrganizationDomainService.EVT_APPROVED.equals(e.getEventType()));

        OrganizationResponse suspended = organizationService.suspendOrganization(pending.id());
        assertThat(suspended.status()).isEqualTo(OrganizationStatus.SUSPENDED);
        ProviderEntity afterSuspend = providerRepository.findByOrganizationId(pending.id()).orElseThrow();
        assertThat(afterSuspend.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void rejectSavesReasonAndPreservesPendingOnlyGuard() {
        String ownerId = "supabase-user-" + UUID.randomUUID();
        OrganizationResponse pending = organizationService.registerOrganization(
                ownerId,
                new RegisterOrganizationRequest(
                        "Cửa Hàng Chưa Đủ Điều Kiện",
                        OrganizationType.COMMERCE,
                        null,
                        "0908887776",
                        "Cần Thơ",
                        null,
                        null));

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
                new RegisterOrganizationRequest("Org A", OrganizationType.SERVICE, null, "0901", "HCM", null, null));
        OrganizationResponse orgB = organizationService.registerOrganization(
                "other-owner-" + UUID.randomUUID(),
                new RegisterOrganizationRequest("Org B", OrganizationType.COMMERCE, null, "0902", "HN", null, null));

        organizationService.approveOrganization(orgA.id(), "admin-1");
        organizationService.approveOrganization(orgB.id(), "admin-1");
        organizationService.addMember(orgB.id(), new AddMemberRequest(multiOrgUser, OrgRole.ORG_STAFF));

        SupabaseRoleClient mockRoleClient = mock(SupabaseRoleClient.class);
        SupabaseRoleSyncConsumer syncConsumer = new SupabaseRoleSyncConsumer(
                memberRepository, mockRoleClient, objectMapper);

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
}
