package com.homecare.booking.security;

import java.util.UUID;

/**
 * Query-level visibility scope for booking list/detail enforcement.
 */
public record BookingReadScope(
        boolean globalAdmin,
        UUID organizationId,
        String requesterProfileId,
        String assignedTechnicianProfileId,
        boolean includeOrganizationBookings) {
}
