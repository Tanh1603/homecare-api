package com.homecare.common.security;

import java.util.UUID;

/**
 * Verifies a user's active membership and role within an organization.
 */
@FunctionalInterface
public interface OrganizationMembershipVerifier {
    VerifiedMembership verify(UUID organizationId, String userId);
}
