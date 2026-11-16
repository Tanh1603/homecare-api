package com.homecare.booking.security;

import com.homecare.booking.entity.BookingEntity;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Booking domain access policy enforcing dual-role read continuity, technician isolation (#7), and tenant boundaries.
 */
@Component
public class BookingAccessPolicy {

    public BookingReadScope readScope(ActorContext actor, String requestedScope) {
        String normalizedScope = requestedScope != null && !requestedScope.isBlank()
                ? requestedScope.trim().toUpperCase()
                : null;
        if (normalizedScope != null && !Set.of("CUSTOMER", "TECHNICIAN").contains(normalizedScope)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }

        if ("CUSTOMER".equals(normalizedScope)) {
            // Dual-role read continuity (#1): requester retains read access across orgs.
            return new BookingReadScope(false, null, actor.userId(), null, false);
        }

        if ("TECHNICIAN".equals(normalizedScope)) {
            if (actor.mode() == AccessMode.ORGANIZATION) {
                boolean orgTechEligible = actor.hasGlobalRole(RbacConstants.ROLE_TECHNICIAN)
                        || actor.hasGlobalPermission(RbacConstants.PERM_BOOKING_UPDATE_STATUS)
                        || actor.hasOrgRole("ORG_TECHNICIAN");
                if (!orgTechEligible) {
                    throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
                }
                return new BookingReadScope(false, actor.organizationId(), null, actor.userId(), false);
            }
            if (!hasPersonalTechnicianCapability(actor)) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return new BookingReadScope(false, null, null, actor.userId(), false);
        }

        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return new BookingReadScope(true, null, null, null, false);
        }

        if (actor.mode() == AccessMode.ORGANIZATION) {
            boolean orgWide = actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF")
                    && actor.hasOrgPermission(RbacConstants.PERM_BOOKING_READ);
            String techId = (hasPersonalTechnicianCapability(actor) || actor.hasOrgRole("ORG_TECHNICIAN"))
                    ? actor.userId()
                    : null;
            return new BookingReadScope(false, actor.organizationId(), actor.userId(), techId, orgWide);
        }

        String techId = hasPersonalTechnicianCapability(actor) ? actor.userId() : null;
        return new BookingReadScope(false, null, actor.userId(), techId, false);
    }

    public boolean isVisible(ActorContext actor, BookingEntity booking) {
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return true;
        }
        if (actor.userId().equals(booking.getRequesterProfileId())) {
            return true;
        }
        if (actor.mode() == AccessMode.ORGANIZATION) {
            if (!actor.organizationId().equals(booking.getProviderId())) {
                return false;
            }
            boolean orgWide = actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF")
                    && actor.hasOrgPermission(RbacConstants.PERM_BOOKING_READ);
            if (orgWide) {
                return true;
            }
            boolean techEligible = hasPersonalTechnicianCapability(actor) || actor.hasOrgRole("ORG_TECHNICIAN");
            return techEligible && actor.userId().equals(booking.getAssignedTechnicianProfileId());
        }
        return hasPersonalTechnicianCapability(actor)
                && actor.userId().equals(booking.getAssignedTechnicianProfileId());
    }

    public void assertCanCancel(ActorContext actor, BookingEntity booking) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            boolean orgDispatch = actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF")
                    && actor.hasOrgPermission(RbacConstants.PERM_BOOKING_DISPATCH);
            if (!orgDispatch || !actor.organizationId().equals(booking.getProviderId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return;
        }
        if (actor.isGlobalAdmin() || actor.userId().equals(booking.getRequesterProfileId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public void assertCanAssign(ActorContext actor, BookingEntity booking) {
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF")
                && actor.hasOrgPermission(RbacConstants.PERM_BOOKING_DISPATCH)
                && (booking.getProviderId() == null || actor.organizationId().equals(booking.getProviderId()))) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public void assertCanAppendWorkUpdate(ActorContext actor, BookingEntity booking) {
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId().equals(booking.getProviderId())
                && actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN", "ORG_STAFF")
                && actor.hasOrgPermission(RbacConstants.PERM_BOOKING_DISPATCH)) {
            return;
        }
        boolean isAssignedTech = actor.userId().equals(booking.getAssignedTechnicianProfileId());
        if (isAssignedTech
                && (actor.mode() == AccessMode.PERSONAL || actor.organizationId().equals(booking.getProviderId()))) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    private boolean hasPersonalTechnicianCapability(ActorContext actor) {
        return actor.hasGlobalRole(RbacConstants.ROLE_TECHNICIAN)
                || actor.hasGlobalPermission(RbacConstants.PERM_BOOKING_UPDATE_STATUS);
    }
}
