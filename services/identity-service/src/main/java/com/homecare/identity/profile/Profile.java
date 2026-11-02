package com.homecare.identity.profile;

import java.time.Instant;
import java.util.UUID;

/** API representation, never a JPA entity or Firebase token payload. */
public record Profile(UUID id, String displayName, String phone, String address, Role role,
                      Status status, Instant createdAt, Instant updatedAt, long version) {
    public enum Role { HOUSEHOLD_USER, SERVICE_PROVIDER, TECHNICIAN, MERCHANT, SYSTEM_ADMIN }
    public enum Status { ACTIVE, DISABLED }
}
