package com.homecare.common.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

class ActorContextResolverTest {

    private static Jwt jwt(String sub, List<String> roles, List<String> permissions) {
        return new Jwt(
                "token-" + sub,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("sub", sub, "user_roles", roles, "permissions", permissions));
    }

    @Test
    void resolvesPersonalModeWhenOrganizationHeaderAbsent() {
        ActorContext ctx = ActorContextResolver.resolve(
                jwt("user-1", List.of("ROLE_CUSTOMER", "technician"), List.of("booking:read")),
                (UUID) null,
                (orgId, userId) -> VerifiedMembership.of(orgId, userId, "ORG_OWNER", true));

        assertThat(ctx.userId()).isEqualTo("user-1");
        assertThat(ctx.mode()).isEqualTo(AccessMode.PERSONAL);
        assertThat(ctx.organizationId()).isNull();
        assertThat(ctx.globalRoles()).containsExactlyInAnyOrder("CUSTOMER", "TECHNICIAN");
        assertThat(ctx.globalPermissions()).containsExactly("booking:read");
        assertThat(ctx.organizationRoles()).isEmpty();
        assertThat(ctx.organizationPermissions()).isEmpty();
    }

    @Test
    void resolvesOrganizationModeWhenMembershipIsActiveAndSeparatesGlobalPermissions() {
        UUID orgId = UUID.randomUUID();
        ActorContext ctx = ActorContextResolver.resolve(
                jwt("tech-1", List.of("TECHNICIAN"), List.of("booking:read")),
                orgId,
                (id, user) -> VerifiedMembership.of(id, user, "ORG_TECHNICIAN", true));

        assertThat(ctx.mode()).isEqualTo(AccessMode.ORGANIZATION);
        assertThat(ctx.organizationId()).isEqualTo(orgId);
        assertThat(ctx.organizationRoles()).containsExactly("ORG_TECHNICIAN");
        assertThat(ctx.globalPermissions()).containsExactly("booking:read");
        assertThat(ctx.organizationPermissions()).isEmpty();
    }

    @Test
    void rejectsOrganizationModeWhenMembershipMissingOrInactiveEvenForAdmin() {
        UUID orgId = UUID.randomUUID();
        OrganizationMembershipVerifier inactiveVerifier = (id, user) -> VerifiedMembership.of(id, user, "ORG_OWNER",
                false);

        assertThatThrownBy(() -> ActorContextResolver.resolve(
                jwt("admin-1", List.of("ADMIN"), List.of("admin:all")),
                orgId,
                inactiveVerifier))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_FORBIDDEN.name());

        assertThatThrownBy(() -> ActorContextResolver.resolve(
                jwt("user-2", List.of("CUSTOMER"), List.of()),
                orgId,
                (id, user) -> null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_FORBIDDEN.name());
    }

    @Test
    void propagatesServiceUnavailableFromMembershipVerifierWithoutSwallowing() {
        UUID orgId = UUID.randomUUID();
        OrganizationMembershipVerifier unavailable = (id, user) -> {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "MEMBERSHIP_UNAVAILABLE", "down");
        };

        assertThatThrownBy(() -> ActorContextResolver.resolve(
                jwt("user-1", List.of("ORGANIZATION"), List.of()),
                orgId,
                unavailable))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void resolvePersonalOnlyRejectsOrganizationHeaderWithForbidden() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> ActorContextResolver.resolvePersonalOnly(
                jwt("user-1", List.of("CUSTOMER"), List.of()),
                orgId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_FORBIDDEN.name());

        ActorContext ctx = ActorContextResolver.resolvePersonalOnly(
                jwt("user-1", List.of("CUSTOMER"), List.of("review:create")),
                null);
        assertThat(ctx.mode()).isEqualTo(AccessMode.PERSONAL);
        assertThat(ctx.userId()).isEqualTo("user-1");
    }

    @Test
    void parseOrganizationIdRejectsInvalidUuidAndUnauthenticatedJwt() {
        assertThatThrownBy(() -> ActorContextResolver.parseOrganizationId("not-a-uuid"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED.name());

        assertThatThrownBy(() -> ActorContextResolver.resolve(null, (UUID) null, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(CommonErrorCode.UNAUTHENTICATED.name());
    }
}
