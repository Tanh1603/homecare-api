package com.homecare.provider.security;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.OrganizationMembershipVerifier;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Provider domain access policy backed by local organization membership verification.
 */
@Component
@RequiredArgsConstructor
public class ProviderAccessPolicy implements OrganizationMembershipVerifier {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    @Override
    public VerifiedMembership verify(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) {
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
        boolean approved = organizationRepository.findById(organizationId)
                .filter(o -> o.getStatus() == OrganizationStatus.APPROVED)
                .isPresent();
        if (!approved) {
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
        return memberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()))
                .map(m -> VerifiedMembership.of(organizationId, userId, m.getRole().name(), true))
                .orElseGet(() -> VerifiedMembership.of(organizationId, userId, null, false));
    }

    public void assertCanManageProvider(ActorContext actor, ProviderEntity provider) {
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return;
        }
        if (actor.mode() != AccessMode.ORGANIZATION
                || !actor.organizationId().equals(provider.getOrganizationId())
                || !actor.hasOrgRole(OrgRole.ORG_OWNER.name(), OrgRole.ORG_ADMIN.name())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
    }

    public void assertCanManageTechnicianSchedule(ActorContext actor, Technician technician) {
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return;
        }
        if (actor.userId().equals(technician.getProfileId())) {
            if (actor.mode() == AccessMode.PERSONAL || actor.organizationId().equals(technician.getProviderId())) {
                return;
            }
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId().equals(technician.getProviderId())
                && actor.hasOrgRole(OrgRole.ORG_OWNER.name(), OrgRole.ORG_ADMIN.name())
                && actor.hasOrgPermission(RbacConstants.PERM_SCHEDULE_MANAGE)) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public boolean canViewScheduleBookingIds(ActorContext actor, Technician technician) {
        if (actor == null) {
            return false;
        }
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return true;
        }
        if (actor.userId().equals(technician.getProfileId())) {
            return actor.mode() == AccessMode.PERSONAL || actor.organizationId().equals(technician.getProviderId());
        }
        return actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId().equals(technician.getProviderId())
                && actor.hasOrgRole(OrgRole.ORG_OWNER.name(), OrgRole.ORG_ADMIN.name(), OrgRole.ORG_STAFF.name());
    }
}
