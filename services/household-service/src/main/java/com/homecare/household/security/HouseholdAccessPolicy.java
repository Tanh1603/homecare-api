package com.homecare.household.security;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Household domain access policy protecting personal households/devices and org-scoped warranties.
 */
@Component
public class HouseholdAccessPolicy {

    public UUID resolveWarrantyOrganizationScope(ActorContext actor, UUID requestedOrgId) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            if (!actor.hasOrgPermission(RbacConstants.PERM_WARRANTY_READ)) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            if (requestedOrgId != null && !requestedOrgId.equals(actor.organizationId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return actor.organizationId();
        }
        if (requestedOrgId != null) {
            if (actor.isGlobalAdmin()) {
                return requestedOrgId;
            }
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return null;
    }
}
