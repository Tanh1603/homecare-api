package com.homecare.identity.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Owner-local persistence metadata; Hibernate validates the SQL-managed schema. */
@Entity @Table(name="identity_idempotency")
public class IdempotencyRow {
    @Id @Column(name="scope_hash", length=64) String scopeHash;
    @Column(name="profile_id", nullable=false) UUID profileId;
    @Column(name="response_body", nullable=false, columnDefinition="text") String responseBody;
    @Column(name="response_status", nullable=false) int responseStatus;
    @Column(name="committed_at", nullable=false) Instant committedAt;
    @Column(name="expires_at", nullable=false) Instant expiresAt;
}
