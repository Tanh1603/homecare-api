package com.homecare.commerce.security;

import java.util.UUID;

/**
 * Query-level visibility scope for commerce orders.
 */
public record OrderReadScope(
        boolean globalAdmin,
        String buyerProfileId,
        UUID merchantId) {
}
