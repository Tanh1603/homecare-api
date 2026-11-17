package com.homecare.commerce.security;

import org.springframework.stereotype.Component;

import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.entity.OrderEntity;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;

/**
 * Commerce domain access policy separating order:read from order:fulfill and
 * order:return_resolve.
 */
@Component
public class CommerceAccessPolicy {

    public OrderReadScope orderReadScope(ActorContext actor, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            if (!actor.hasOrgPermission(RbacConstants.PERM_ORDER_READ)
                    || merchant == null
                    || !EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                    || !actor.organizationId().equals(merchant.getOrganizationId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return new OrderReadScope(false, null, merchant.getId());
        }
        if (actor.isGlobalAdmin()) {
            return new OrderReadScope(true, null, null);
        }
        return new OrderReadScope(false, actor.userId(), null);
    }

    public boolean isOrderVisible(ActorContext actor, OrderEntity order, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            return actor.hasOrgPermission(RbacConstants.PERM_ORDER_READ)
                    && merchant != null
                    && EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                    && actor.organizationId().equals(merchant.getOrganizationId());
        }
        return actor.isGlobalAdmin() || actor.userId().equals(order.getBuyerProfileId());
    }

    public void assertCanFulfillOrder(ActorContext actor, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.PERSONAL && actor.isGlobalAdmin()) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.hasOrgPermission(RbacConstants.PERM_ORDER_FULFILL)
                && merchant != null
                && EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                && actor.organizationId().equals(merchant.getOrganizationId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public void assertCanResolveReturn(ActorContext actor, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.PERSONAL && actor.isGlobalAdmin()) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.hasOrgPermission(RbacConstants.PERM_ORDER_RETURN_RESOLVE)
                && merchant != null
                && EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                && actor.organizationId().equals(merchant.getOrganizationId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public boolean canListOrderReturns(ActorContext actor, OrderEntity order, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            return isOrderVisible(actor, order, merchant);
        }
        return actor.isGlobalAdmin() || actor.userId().equals(order.getBuyerProfileId());
    }

    public void assertCanManageCatalog(ActorContext actor, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.PERSONAL && actor.isGlobalAdmin()) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.hasOrgPermission(RbacConstants.PERM_PRODUCT_MANAGE)
                && merchant != null
                && EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                && actor.organizationId().equals(merchant.getOrganizationId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    public void assertCanCancelOrder(ActorContext actor, OrderEntity order, MerchantEntity merchant) {
        if (actor.mode() == AccessMode.ORGANIZATION) {
            if (actor.hasOrgPermission(RbacConstants.PERM_ORDER_FULFILL)
                    && merchant != null
                    && EntityStatus.ACTIVE.name().equalsIgnoreCase(merchant.getStatus())
                    && actor.organizationId().equals(merchant.getOrganizationId())) {
                return;
            }
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (actor.isGlobalAdmin() || actor.userId().equals(order.getBuyerProfileId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }
}
