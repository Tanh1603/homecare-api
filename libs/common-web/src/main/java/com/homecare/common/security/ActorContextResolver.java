package com.homecare.common.security;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves an immutable {@link ActorContext} from a verified Supabase {@link Jwt} and optional {@code X-Organization-Id}.
 */
public final class ActorContextResolver {

    private ActorContextResolver() {
    }

    public static UUID parseOrganizationId(String rawOrgId) {
        if (rawOrgId == null || rawOrgId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawOrgId.trim());
        } catch (IllegalArgumentException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid X-Organization-Id header.");
        }
    }

    public static ActorContext resolvePersonalOnly(Jwt jwt, UUID requestedOrgId) {
        if (requestedOrgId != null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return resolve(jwt, null, null);
    }

    public static ActorContext resolve(
            Jwt jwt, UUID requestedOrgId, OrganizationMembershipVerifier membershipVerifier) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new DomainException(CommonErrorCode.UNAUTHENTICATED);
        }
        String userId = jwt.getSubject();
        Set<String> globalRoles = normalizeRoles(SupabaseJwtConverterSupport.extractRoles(jwt));
        Set<String> globalPermissions = SupabaseJwtConverterSupport.extractPermissions(jwt);

        if (requestedOrgId == null) {
            return new ActorContext(
                    userId,
                    AccessMode.PERSONAL,
                    null,
                    globalRoles,
                    globalPermissions,
                    Set.of(),
                    Set.of());
        }

        if (membershipVerifier == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }

        VerifiedMembership membership = membershipVerifier.verify(requestedOrgId, userId);
        if (membership == null
                || !membership.active()
                || !requestedOrgId.equals(membership.organizationId())
                || membership.role() == null
                || membership.role().isBlank()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }

        String normalizedOrgRole = membership.role().trim().toUpperCase();
        return new ActorContext(
                userId,
                AccessMode.ORGANIZATION,
                requestedOrgId,
                globalRoles,
                globalPermissions,
                Set.of(normalizedOrgRole),
                membership.permissions());
    }

    private static Set<String> normalizeRoles(Set<String> rawRoles) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : rawRoles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String upper = role.trim().toUpperCase();
            if (upper.startsWith("ROLE_")) {
                upper = upper.substring(5);
            }
            if (!upper.isBlank()) {
                normalized.add(upper);
            }
        }
        return Set.copyOf(normalized);
    }
}
