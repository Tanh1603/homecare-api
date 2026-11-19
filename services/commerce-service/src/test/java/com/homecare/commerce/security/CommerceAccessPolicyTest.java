package com.homecare.commerce.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;

class CommerceAccessPolicyTest {
    private final CommerceAccessPolicy policy = new CommerceAccessPolicy();
    private final UUID organizationId = UUID.randomUUID();
    private final MerchantEntity merchant = MerchantEntity.builder()
            .id(UUID.randomUUID())
            .organizationId(organizationId)
            .ownerProfileId("owner")
            .name("Merchant")
            .city("HCMC")
            .ratingAvg(0)
            .status("ACTIVE")
            .createdAt(Instant.now())
            .build();

    @Test
    void onlyOrgOwnerCanConfirmManualRefundInOrganizationMode() {
        ActorContext owner = actor("owner", Set.of(RbacConstants.ORG_ROLE_OWNER));
        ActorContext staff = actor("staff", Set.of(RbacConstants.ORG_ROLE_STAFF));

        assertThatCode(() -> policy.assertCanConfirmManualRefund(owner, merchant)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertCanConfirmManualRefund(staff, merchant))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
    }

    private ActorContext actor(String userId, Set<String> organizationRoles) {
        return new ActorContext(
                userId,
                AccessMode.ORGANIZATION,
                organizationId,
                Set.of(),
                Set.of(),
                organizationRoles,
                Set.of());
    }
}
